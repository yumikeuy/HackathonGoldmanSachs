/**
 * Login session (docs/security.md, `POST /api/auth/*`).
 *
 * - Access token (JWT, 15 min): kept in memory only, sent as `Authorization: Bearer …` and in STOMP CONNECT.
 * - Refresh token (one-time, 7 days): kept in sessionStorage so a page reload stays logged in; every refresh
 *   returns a new pair (rotation). Concurrent 401s share one refresh.
 * - Whether the server requires login at all (`JWT_ENABLED`) is probed once at startup: without it the UI works
 *   exactly as before and login is optional.
 */

export type Role = 'VIEWER' | 'OPERATOR' | 'ADMIN';

export interface SessionUser {
  subject: string;
  email: string;
  name: string;
  role: Role;
  teamId?: number | null;
}

interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: SessionUser;
}

const REFRESH_KEY = 'alerting.refreshToken';

let accessToken: string | null = null;
let user: SessionUser | null = null;
let refreshing: Promise<boolean> | null = null;
const listeners = new Set<() => void>();

function notify() {
  listeners.forEach(l => l());
}

function store(tokens: TokenResponse) {
  accessToken = tokens.accessToken;
  user = tokens.user;
  try { sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken); } catch { /* private mode: no persistence */ }
  notify();
}

function clear() {
  accessToken = null;
  user = null;
  try { sessionStorage.removeItem(REFRESH_KEY); } catch { /* ignore */ }
  notify();
}

function storedRefreshToken(): string | null {
  try { return sessionStorage.getItem(REFRESH_KEY); } catch { return null; }
}

async function postJson(path: string, body: unknown): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, application/problem+json' },
    body: JSON.stringify(body),
  });
}

export const session = {
  get accessToken() { return accessToken; },
  get user() { return user; },
  get loggedIn() { return accessToken !== null; },

  /** Subscribe to login/logout/refresh; returns the unsubscribe function. */
  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  /** @returns null on success, otherwise a message to show */
  async login(email: string, password: string): Promise<string | null> {
    let res: Response;
    try {
      res = await postJson('/api/auth/login', { email, password });
    } catch {
      return 'The server cannot be reached. Is the backend running?';
    }
    if (res.ok) {
      store(await res.json() as TokenResponse);
      return null;
    }
    if (res.status === 429) return 'Too many attempts — wait a minute and try again.';
    if (res.status === 401) return 'Wrong email or password.';
    try {
      const problem = await res.json() as { detail?: string };
      return problem.detail ?? `Login failed (${res.status})`;
    } catch {
      return `Login failed (${res.status})`;
    }
  },

  /** Uses the stored refresh token to get a new pair. One request at a time; false if the session is over. */
  refresh(): Promise<boolean> {
    if (!refreshing) {
      refreshing = (async () => {
        const token = storedRefreshToken();
        if (!token) return false;
        try {
          const res = await postJson('/api/auth/refresh', { refreshToken: token });
          if (!res.ok) {
            clear();
            return false;
          }
          store(await res.json() as TokenResponse);
          return true;
        } catch {
          return false;
        }
      })().finally(() => { refreshing = null; });
    }
    return refreshing;
  },

  async logout() {
    const token = storedRefreshToken();
    const bearer = accessToken;
    clear();
    if (token) {
      try {
        await fetch('/api/auth/logout', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) },
          body: JSON.stringify({ refreshToken: token }),
        });
      } catch { /* the token expires anyway */ }
    }
  },

  /** The session ended on the server side (refresh failed): forget it so the login screen shows. */
  expire() {
    clear();
  },

  /**
   * Whether the server enforces login: an anonymous read of a protected endpoint. 401 → required.
   * Network errors count as "not required" so the usual "server unreachable" errors show instead.
   */
  async probeLoginRequired(): Promise<boolean> {
    try {
      const res = await fetch('/api/teams', { headers: { Accept: 'application/json' } });
      return res.status === 401;
    } catch {
      return false;
    }
  },
};
