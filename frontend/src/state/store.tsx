/**
 * Application state: configuration (teams, rules, runbooks, users, sources), the merged alert feed of all teams
 * plus UNROUTED, live updates over STOMP (with pause/resume), and global UI state (alert drawer, rule editor).
 *
 * `GET /api/alerts` takes exactly one of `teamId` / `unrouted`, so the feed is loaded per team and merged here;
 * live updates come from `/topic/alerts/{teamId}` for every team and `/topic/unrouted`.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import * as api from '../api/endpoints';
import { errorText } from '../api/client';
import { live, type LiveStatus } from '../ws/live';
import { useToast } from '../components/Toast';
import { SEV } from '../components/ui';
import type { AlertSummary, Delivery, FieldInfo, Rule, RuleInput, Runbook, Source, Team, User } from '../types';

export interface RuleEditorState {
  rule?: Rule;
  prefill?: Partial<RuleInput>;
  /** "Create rule from event": the event the rule is built from. */
  origin?: { source: string | null; payload: unknown; alertId?: number; suggestedTeam?: string };
}

interface Store {
  teams: Team[]; rules: Rule[]; runbooks: Runbook[]; users: User[]; sources: Source[];
  configLoaded: boolean; configError: string | null;
  reloadTeams: () => Promise<void>; reloadRules: () => Promise<void>; reloadRunbooks: () => Promise<void>;
  reloadUsers: () => Promise<void>; reloadSources: () => Promise<void>;
  teamName: (id: number | null | undefined) => string | null;

  alerts: AlertSummary[]; alertsLoaded: boolean; resolvedLoaded: boolean;
  loadResolved: () => Promise<void>; reloadAlerts: () => Promise<void>;
  upsertAlert: (a: AlertSummary) => void;
  /** id → time of the last occurrence-count increase (for the bump animation). */
  bumps: Record<number, number>;
  /** id → time the alert arrived live (for the row highlight). */
  fresh: Record<number, number>;

  liveStatus: LiveStatus; paused: boolean; togglePaused: () => void;
  onDelivery: (cb: (d: Delivery) => void) => () => void;

  failedCount: number; failedMore: boolean; refreshFailed: () => Promise<void>;

  fields: FieldInfo[]; loadFields: () => Promise<void>;

  openAlertId: number | null; openAlert: (id: number) => void; closeAlert: () => void;
  ruleEditor: RuleEditorState | null; openRuleEditor: (s: RuleEditorState) => void; closeRuleEditor: () => void;
}

const Ctx = createContext<Store | null>(null);

export function useStore(): Store {
  const s = useContext(Ctx);
  if (!s) throw new Error('useStore outside StoreProvider');
  return s;
}

export function StoreProvider({ children }: { children: ReactNode }) {
  const toast = useToast();

  /* ---------------- configuration ---------------- */
  const [teams, setTeams] = useState<Team[]>([]);
  const [rules, setRules] = useState<Rule[]>([]);
  const [runbooks, setRunbooks] = useState<Runbook[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [sources, setSources] = useState<Source[]>([]);
  const [configLoaded, setConfigLoaded] = useState(false);
  const [configError, setConfigError] = useState<string | null>(null);

  const reloadTeams = useCallback(async () => setTeams(await api.getTeams()), []);
  const reloadRules = useCallback(async () => setRules(await api.getRules()), []);
  const reloadRunbooks = useCallback(async () => setRunbooks(await api.getRunbooks()), []);
  const reloadUsers = useCallback(async () => setUsers(await api.getUsers()), []);
  const reloadSources = useCallback(async () => setSources(await api.getSources()), []);

  useEffect(() => {
    Promise.all([reloadTeams(), reloadRules(), reloadRunbooks(), reloadUsers(), reloadSources()])
      .then(() => setConfigError(null))
      .catch(e => setConfigError(errorText(e)))
      .finally(() => setConfigLoaded(true));
  }, [reloadTeams, reloadRules, reloadRunbooks, reloadUsers, reloadSources]);

  const teamMap = useMemo(() => new Map(teams.map(t => [t.id, t.name])), [teams]);
  const teamName = useCallback((id: number | null | undefined) => (id == null ? null : teamMap.get(id) ?? `Team #${id}`), [teamMap]);

  /* ---------------- alerts ---------------- */
  const [alertMap, setAlertMap] = useState<Map<number, AlertSummary>>(new Map());
  const [alertsLoaded, setAlertsLoaded] = useState(false);
  const [resolvedLoaded, setResolvedLoaded] = useState(false);
  const [bumps, setBumps] = useState<Record<number, number>>({});
  const [fresh, setFresh] = useState<Record<number, number>>({});
  const known = useRef<Map<number, AlertSummary>>(new Map());
  known.current = alertMap;

  const merge = useCallback((list: AlertSummary[]) => {
    setAlertMap(prev => {
      const next = new Map(prev);
      list.forEach(a => next.set(a.id, a));
      return next;
    });
  }, []);

  const teamIds = useMemo(() => teams.map(t => t.id).sort((a, b) => a - b).join(','), [teams]);

  const fetchAll = useCallback(async (status: AlertSummary['status'][]) => {
    const ids = teamIds ? teamIds.split(',').map(Number) : [];
    const pages = await Promise.all([
      ...ids.map(teamId => api.getAlerts({ teamId, status })),
      api.getAlerts({ unrouted: true, status }),
    ]);
    return pages.flatMap(p => p.items);
  }, [teamIds]);

  const reloadAlerts = useCallback(async () => {
    try {
      merge(await fetchAll(['OPEN', 'ACKED']));
    } catch (e) {
      toast({ title: 'Could not load alerts', body: errorText(e), tone: 'err' });
    } finally {
      setAlertsLoaded(true);
    }
  }, [fetchAll, merge, toast]);

  const loadResolved = useCallback(async () => {
    try {
      merge(await fetchAll(['RESOLVED']));
      setResolvedLoaded(true);
    } catch (e) {
      toast({ title: 'Could not load resolved alerts', body: errorText(e), tone: 'err' });
    }
  }, [fetchAll, merge, toast]);

  useEffect(() => {
    if (!configLoaded) return;
    reloadAlerts();
    setResolvedLoaded(false);
  }, [configLoaded, teamIds, reloadAlerts]);

  const upsertAlert = useCallback((a: AlertSummary) => merge([a]), [merge]);

  /* ---------------- live updates ---------------- */
  const [liveStatus, setLiveStatus] = useState<LiveStatus>(live.status);
  const [paused, setPaused] = useState(false);
  const pausedRef = useRef(false);
  const queue = useRef<AlertSummary[]>([]);
  const openRef = useRef<(id: number) => void>(() => {});
  const teamNameRef = useRef(teamName);
  teamNameRef.current = teamName;
  const loadedRef = useRef(false);
  loadedRef.current = alertsLoaded;

  const applyQueue = useCallback(() => {
    if (!queue.current.length) return;
    const batch = queue.current;
    queue.current = [];
    const now = Date.now();
    const newBumps: Record<number, number> = {};
    const newFresh: Record<number, number> = {};
    const byId = new Map<number, AlertSummary>();
    batch.forEach(a => byId.set(a.id, a));
    for (const a of byId.values()) {
      const prev = known.current.get(a.id);
      if (!prev) {
        if (loadedRef.current) newFresh[a.id] = now;
        if (loadedRef.current && a.teamId != null && (a.severity === 'CRITICAL' || a.severity === 'HIGH') && a.status === 'OPEN') {
          toast({
            title: `New ${SEV[a.severity].l.toLowerCase()} alert`,
            body: `${a.title} · ${teamNameRef.current(a.teamId)}`,
            tone: a.severity === 'CRITICAL' ? 'crit' : 'warn',
            action: { label: 'Open', onClick: () => openRef.current(a.id) },
          });
        }
      } else if (a.occurrencesCount > prev.occurrencesCount) {
        newBumps[a.id] = now;
      }
    }
    merge([...byId.values()]);
    if (Object.keys(newBumps).length) setBumps(b => ({ ...b, ...newBumps }));
    if (Object.keys(newFresh).length) setFresh(f => ({ ...f, ...newFresh }));
  }, [merge, toast]);

  useEffect(() => {
    live.start();
    let prev = live.status;
    return live.onStatus(s => {
      // After a reconnect, refetch so updates missed while disconnected show up.
      if (s === 'live' && prev !== 'live' && loadedRef.current) reloadAlerts();
      prev = s;
      setLiveStatus(s);
    });
  }, [reloadAlerts]);

  useEffect(() => {
    const iv = setInterval(() => { if (!pausedRef.current) applyQueue(); }, 250);
    return () => clearInterval(iv);
  }, [applyQueue]);

  useEffect(() => {
    const push = (body: unknown) => { queue.current.push(body as AlertSummary); };
    const unsubs = [live.subscribe('/topic/unrouted', push)];
    if (teamIds) teamIds.split(',').forEach(id => unsubs.push(live.subscribe(`/topic/alerts/${id}`, push)));
    return () => unsubs.forEach(u => u());
  }, [teamIds]);

  const togglePaused = useCallback(() => {
    pausedRef.current = !pausedRef.current;
    setPaused(pausedRef.current);
    if (!pausedRef.current) {
      applyQueue();
      toast({ title: 'Live updates resumed', tone: 'ok' });
    }
  }, [applyQueue, toast]);

  const deliveryListeners = useRef(new Set<(d: Delivery) => void>());
  useEffect(() => live.subscribe('/topic/simulator', body => deliveryListeners.current.forEach(l => l(body as Delivery))), []);
  const onDelivery = useCallback((cb: (d: Delivery) => void) => {
    deliveryListeners.current.add(cb);
    return () => { deliveryListeners.current.delete(cb); };
  }, []);

  /* ---------------- failed events badge ---------------- */
  const [failedCount, setFailedCount] = useState(0);
  const [failedMore, setFailedMore] = useState(false);
  const refreshFailed = useCallback(async () => {
    try {
      const p = await api.getFailedEvents(null, 200);
      setFailedCount(p.items.length);
      setFailedMore(!!p.nextCursor);
    } catch { /* badge only */ }
  }, []);
  useEffect(() => {
    refreshFailed();
    const iv = setInterval(refreshFailed, 15000);
    return () => clearInterval(iv);
  }, [refreshFailed]);

  /* ---------------- field catalog (autocomplete) ---------------- */
  const [fields, setFields] = useState<FieldInfo[]>([]);
  const fieldsAt = useRef(0);
  const loadFields = useCallback(async () => {
    if (Date.now() - fieldsAt.current < 10000) return;
    fieldsAt.current = Date.now();
    try { setFields(await api.getFields()); } catch { /* autocomplete is optional */ }
  }, []);

  /* ---------------- global UI ---------------- */
  const [openAlertId, setOpenAlertId] = useState<number | null>(null);
  const openAlert = useCallback((id: number) => setOpenAlertId(id), []);
  openRef.current = openAlert;
  const closeAlert = useCallback(() => setOpenAlertId(null), []);
  const [ruleEditor, setRuleEditor] = useState<RuleEditorState | null>(null);
  const openRuleEditor = useCallback((s: RuleEditorState) => { setOpenAlertId(null); setRuleEditor(s); }, []);
  const closeRuleEditor = useCallback(() => setRuleEditor(null), []);

  const alerts = useMemo(() => [...alertMap.values()], [alertMap]);

  const value: Store = {
    teams, rules, runbooks, users, sources, configLoaded, configError,
    reloadTeams, reloadRules, reloadRunbooks, reloadUsers, reloadSources, teamName,
    alerts, alertsLoaded, resolvedLoaded, loadResolved, reloadAlerts, upsertAlert, bumps, fresh,
    liveStatus, paused, togglePaused, onDelivery,
    failedCount, failedMore, refreshFailed,
    fields, loadFields,
    openAlertId, openAlert, closeAlert, ruleEditor, openRuleEditor, closeRuleEditor,
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
