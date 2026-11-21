/**
 * Thin fetch wrapper for the backend REST API (`frontend/openapi.yaml`).
 * Errors are RFC 9457 problem+json; `ApiError.detail` is human-readable and safe to show.
 * When logged in, every request carries the access token; a 401 triggers one token refresh and one retry,
 * and if that fails the session ends (the login screen shows).
 */
import type { Problem } from '../types';
import { session } from '../auth/session';

export class ApiError extends Error {
  readonly status: number;
  readonly detail: string;
  readonly problem: Problem | null;

  constructor(status: number, detail: string, problem: Problem | null = null) {
    super(detail);
    this.status = status;
    this.detail = detail;
    this.problem = problem;
  }
}

/** Friendly fallback text when the server gave no `detail`. */
function statusText(status: number): string {
  switch (status) {
    case 0: return 'The server cannot be reached. Is the backend running?';
    case 401: return 'Your session has expired — please log in again.';
    case 403: return 'You are not allowed to do this.';
    case 404: return 'Not found — it may have been deleted.';
    case 409: return 'This conflicts with existing data.';
    case 413: return 'The file is larger than the server accepts (10 MB).';
    case 429: return 'Too many requests — try again in a moment.';
    case 503: return 'A backend dependency (Kafka) is unavailable — try again later.';
    default: return `Request failed (${status})`;
  }
}

async function toError(res: Response): Promise<ApiError> {
  let problem: Problem | null = null;
  try {
    const text = await res.text();
    if (text) problem = JSON.parse(text) as Problem;
  } catch {
    /* not JSON */
  }
  return new ApiError(res.status, problem?.detail || statusText(res.status), problem);
}

type Query = Record<string, string | number | boolean | null | undefined | Array<string | number>>;

function withQuery(path: string, query?: Query): string {
  if (!query) return path;
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) v.forEach(x => qs.append(k, String(x)));
    else qs.append(k, String(v));
  }
  const s = qs.toString();
  return s ? `${path}?${s}` : path;
}

export interface RequestOptions {
  query?: Query;
  body?: unknown;
  form?: FormData;
  signal?: AbortSignal;
}

function authHeaders(): Record<string, string> {
  return session.accessToken ? { Authorization: `Bearer ${session.accessToken}` } : {};
}

export async function request<T>(method: string, path: string, opts: RequestOptions = {}): Promise<T> {
  let body: BodyInit | undefined;
  const base: Record<string, string> = { Accept: 'application/json, application/problem+json' };
  if (opts.form) body = opts.form;
  else if (opts.body !== undefined) {
    base['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  const send = async (): Promise<Response> => {
    try {
      return await fetch(withQuery(path, opts.query), { method, headers: { ...base, ...authHeaders() }, body, signal: opts.signal });
    } catch (e) {
      if ((e as Error).name === 'AbortError') throw e;
      throw new ApiError(0, statusText(0));
    }
  };
  let res = await send();
  if (res.status === 401 && session.loggedIn) {
    // access token expired: refresh once, retry once; a failed refresh ends the session
    if (await session.refresh()) res = await send();
    else session.expire();
  }
  if (!res.ok) throw await toError(res);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const http = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, { body }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body }),
  del: (path: string) => request<void>('DELETE', path),
};

/**
 * Multipart upload with progress (fetch has no upload progress). Used for runbooks.
 * A too-large body makes Tomcat answer 413 and close the connection, so the browser may only see a network
 * error — callers check the size first (≤ 10 MB) and this maps a failed upload to a readable message.
 */
export async function upload<T>(method: 'POST' | 'PUT', path: string, form: FormData, onProgress?: (pct: number) => void): Promise<T> {
  try {
    return await uploadOnce<T>(method, path, form, onProgress);
  } catch (e) {
    if (e instanceof ApiError && e.status === 401 && session.loggedIn && await session.refresh()) {
      return uploadOnce<T>(method, path, form, onProgress);
    }
    throw e;
  }
}

function uploadOnce<T>(method: 'POST' | 'PUT', path: string, form: FormData, onProgress?: (pct: number) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open(method, path);
    xhr.setRequestHeader('Accept', 'application/json, application/problem+json');
    Object.entries(authHeaders()).forEach(([k, v]) => xhr.setRequestHeader(k, v));
    xhr.upload.onprogress = e => { if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100)); };
    xhr.onload = () => {
      let parsed: unknown = undefined;
      try { parsed = xhr.responseText ? JSON.parse(xhr.responseText) : undefined; } catch { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(parsed as T);
      else {
        const p = parsed as Problem | undefined;
        reject(new ApiError(xhr.status, p?.detail || statusText(xhr.status), p ?? null));
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'Upload failed — the connection was closed. Files must be 10 MB or smaller.'));
    xhr.send(form);
  });
}

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.detail;
  if (e instanceof Error) return e.message;
  return String(e);
}
