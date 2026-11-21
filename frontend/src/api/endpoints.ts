/** One function per operation of `frontend/openapi.yaml`. */
import { http, request, upload } from './client';
import type {
  AlertDetail, AlertPage, AlertStatus, AuditEntry, ChannelState, Channel, Condition, DeliveryPage, DryRunRequest,
  DryRunResult, FailedEventPage, FieldInfo, GenerateAccepted, GenerateRequest, IngestLag, Rule, RuleInput, Runbook,
  Source, SourceApiKey, SourceInput, Team, TeamInput, TeamSuggestion, User, UserInput,
} from '../types';

export const MAX_RUNBOOK_BYTES = 10 * 1024 * 1024;

/* ---------- ingest (Role 1) ---------- */
export const getLag = () => http.get<IngestLag>('/api/ingest/lag');
export const getFields = () => http.get<FieldInfo[]>('/api/fields');
export const getFailedEvents = (cursor?: string | null, limit = 100) =>
  http.get<FailedEventPage>('/api/events/failed', { cursor, limit });
export const replayEvent = (eventId: string) => http.post<void>(`/api/events/${eventId}/replay`);

/* ---------- config (Role 1) ---------- */
export const getSources = () => http.get<Source[]>('/api/sources');
export const createSource = (s: SourceInput) => http.post<Source>('/api/sources', s);
export const updateSource = (id: number, s: SourceInput) => http.put<Source>(`/api/sources/${id}`, s);
export const deleteSource = (id: number) => http.del(`/api/sources/${id}`);
export const createApiKey = (id: number) => http.post<SourceApiKey>(`/api/sources/${id}/api-key`);
export const deleteApiKey = (id: number) => http.del(`/api/sources/${id}/api-key`);

export const getTeams = () => http.get<Team[]>('/api/teams');
export const createTeam = (t: TeamInput) => http.post<Team>('/api/teams', t);
export const updateTeam = (id: number, t: TeamInput) => http.put<Team>(`/api/teams/${id}`, t);
export const deleteTeam = (id: number) => http.del(`/api/teams/${id}`);

export const getUsers = (teamId?: number) => http.get<User[]>('/api/users', { teamId });
export const createUser = (u: UserInput) => http.post<User>('/api/users', u);
export const updateUser = (id: number, u: UserInput) => http.put<User>(`/api/users/${id}`, u);
export const deleteUser = (id: number) => http.del(`/api/users/${id}`);

/* ---------- simulator (Role 1) ---------- */
export const getDeliveries = (cursor?: string | null, limit = 100) =>
  http.get<DeliveryPage>('/api/simulator/deliveries', { cursor, limit });
export const getChannels = () => http.get<ChannelState[]>('/api/simulator/channels');
export const toggleChannel = (c: Channel) => http.post<ChannelState>(`/api/simulator/channels/${c}/toggle`);

/* ---------- rules (Role 2) ---------- */
export const getRules = (teamId?: number) => http.get<Rule[]>('/api/rules', { teamId });
export const createRule = (r: RuleInput) => http.post<Rule>('/api/rules', r);
export const updateRule = (id: number, r: RuleInput) => http.put<Rule>(`/api/rules/${id}`, r);
export const deleteRule = (id: number) => http.del(`/api/rules/${id}`);
export const dryRun = (req: DryRunRequest, signal?: AbortSignal) =>
  // POST with an abort signal: dry-runs fire while typing, older ones are cancelled.
  request<DryRunResult>('POST', '/api/rules/dry-run', { body: req, signal });

/* ---------- alerts (Role 2; audit Role 3) ---------- */
export interface AlertQuery { teamId?: number; unrouted?: boolean; status?: AlertStatus[]; cursor?: string | null; limit?: number }
export const getAlerts = (q: AlertQuery) =>
  http.get<AlertPage>('/api/alerts', {
    teamId: q.teamId, unrouted: q.unrouted ? true : undefined, status: q.status, cursor: q.cursor, limit: q.limit ?? 200,
  });
export const getAlert = (id: number) => http.get<AlertDetail>(`/api/alerts/${id}`);
export const ackAlert = (id: number, actor = 'ui') => http.post<AlertDetail>(`/api/alerts/${id}/ack`, { actor });
export const resolveAlert = (id: number, actor = 'ui') => http.post<AlertDetail>(`/api/alerts/${id}/resolve`, { actor });
export const getAudit = (id: number) => http.get<AuditEntry[]>(`/api/alerts/${id}/audit`);
export const getTeamSuggestions = (id: number) => http.get<TeamSuggestion[]>(`/api/alerts/${id}/team-suggestions`);

/* ---------- runbooks (Role 3) ---------- */
export const getRunbooks = (teamId?: number) => http.get<Runbook[]>('/api/runbooks', { teamId });
export const deleteRunbook = (id: number) => http.del(`/api/runbooks/${id}`);
export const runbookFileUrl = (id: number) => `/api/runbooks/${id}/file`;

export interface RunbookForm { file?: File | null; teamId?: number | null; condition?: Condition | null }
function runbookFormData(f: RunbookForm): FormData {
  const fd = new FormData();
  if (f.file) fd.append('file', f.file, f.file.name);
  if (f.teamId != null) fd.append('teamId', String(f.teamId));
  if (f.condition) fd.append('condition', JSON.stringify(f.condition));
  return fd;
}
/** Throws before sending when the file is over 10 MB (the server would close the connection). */
function checkSize(file?: File | null) {
  if (file && file.size > MAX_RUNBOOK_BYTES) throw new Error(`${file.name} is larger than 10 MB.`);
}
export const createRunbook = (f: RunbookForm, onProgress?: (pct: number) => void) => {
  checkSize(f.file);
  return upload<Runbook>('POST', '/api/runbooks', runbookFormData(f), onProgress);
};
export const updateRunbook = (id: number, f: RunbookForm, onProgress?: (pct: number) => void) => {
  checkSize(f.file);
  return upload<Runbook>('PUT', `/api/runbooks/${id}`, runbookFormData(f), onProgress);
};

/* ---------- dev (Role 2) ---------- */
export const generate = (req: GenerateRequest) => http.post<GenerateAccepted>('/api/dev/generate', req);
