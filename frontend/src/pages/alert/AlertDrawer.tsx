/** Alert card (right drawer): detail, runbook, payload, notifications and audit trail. */
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BellRing, Check, CircleCheck, CircleCheckBig, Copy, Download, Eye, Loader, Paperclip, Plus, Unlink, Workflow, X, type LucideIcon,
} from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { Ago, ChanBadge, FileGlyph, JsonView, OutPill, PayloadKV, SevPill, Status, TeamName, cx } from '../../components/ui';
import { copyText, fmtDT, unroutedSource } from '../../utils/format';
import { groupingLabel } from '../../utils/rules';
import { ruleFromEvent } from '../../utils/ruleFromEvent';
import { Occ } from '../feed/AlertsPage';
import { ResolveModal } from './ResolveModal';
import { TeamSuggestions } from './TeamSuggestions';
import type { AlertDetail, AuditEntry, Delivery } from '../../types';
import { usePermissions } from '../../auth/permissions';

const AUDIT: Record<string, [LucideIcon, string]> = {
  ALERT_CREATED: [BellRing, 'bg-red-50 text-red-600'],
  RUNBOOK_ATTACHED: [Paperclip, 'bg-gray-100 text-gray-600'],
  RUNBOOK_DETACHED: [Unlink, 'bg-gray-100 text-gray-500'],
  ACK: [Eye, 'bg-amber-50 text-amber-600'],
  RESOLVE: [CircleCheck, 'bg-emerald-50 text-emerald-600'],
  AUTO_CLOSE: [CircleCheckBig, 'bg-emerald-50 text-emerald-600'],
};

export function AlertDrawer() {
  const { openAlertId, closeAlert, alerts, rules, runbooks, users, teamName, upsertAlert, onDelivery, openRuleEditor } = useStore();
  const toast = useToast();
  const { admin, operate } = usePermissions();
  const summary = alerts.find(a => a.id === openAlertId) ?? null;
  const [detail, setDetail] = useState<AlertDetail | null>(null);
  const [audit, setAudit] = useState<AuditEntry[]>([]);
  const [deliveries, setDeliveries] = useState<Delivery[] | null>(null);
  const [error, setError] = useState('');
  const [raw, setRaw] = useState(false);
  const [resolving, setResolving] = useState(false);
  const [acking, setAcking] = useState(false);
  const open = openAlertId != null;

  const load = useCallback(async (id: number) => {
    try {
      const [d, au] = await Promise.all([api.getAlert(id), api.getAudit(id)]);
      setDetail(d);
      setAudit(au);
      setError('');
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  // Load on open; reload audit + detail when the status or counter changes live.
  useEffect(() => {
    if (openAlertId == null) return;
    setDetail(null); setAudit([]); setDeliveries(null); setRaw(false); setError('');
    load(openAlertId);
    // the delivery log is simulator data, for administrators only (team isolation, docs/security.md)
    if (admin) {
      api.getDeliveries(null, 200)
        .then(p => setDeliveries(p.items.filter(d => d.alertId === openAlertId)))
        .catch(() => setDeliveries([]));
    }
  }, [openAlertId, load, admin]);

  const statusKey = summary ? `${summary.status}|${summary.runbookId}` : '';
  useEffect(() => { if (openAlertId != null && detail) load(openAlertId); }, [statusKey]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => onDelivery(d => {
    if (d.alertId !== openAlertId) return;
    setDeliveries(xs => {
      const list = xs ?? [];
      const i = list.findIndex(x => x.id === d.id);
      if (i < 0) return [d, ...list];
      const n = list.slice(); n[i] = d; return n;
    });
  }), [onDelivery, openAlertId]);

  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape' && !resolving) closeAlert(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, resolving, closeAlert]);

  const a = useMemo(() => (summary && detail ? { ...detail, ...summary } : detail ?? summary), [summary, detail]);
  const rule = rules.find(r => r.id === a?.ruleId);
  const rbId = a?.runbookId ?? detail?.runbook?.id ?? null;
  const rb = runbooks.find(r => r.id === rbId);
  const rbName = detail?.runbook?.fileName ?? rb?.fileName;
  const source = a ? unroutedSource(a.title) : null;
  const unrouted = a != null && a.teamId == null;

  const actorLabel = (act: string) => {
    if (act === 'system') return 'Alerting (automatic)';
    if (act === 'ui') return 'an operator (UI)';
    const m = /^rule:(\d+)$/.exec(act);
    if (m) return `rule “${rules.find(r => r.id === +m[1])?.name ?? '#' + m[1]}”`;
    return users.find(u => u.email === act)?.name ?? act;
  };

  const auditText = (e: AuditEntry) => {
    const d = (e.details ?? {}) as Record<string, unknown>;
    switch (e.actionType) {
      case 'ALERT_CREATED': return unrouted ? 'Alert opened: the event matched no team' : `Alert opened by ${rule ? `rule “${rule.name}”` : 'a rule'}`;
      case 'RUNBOOK_ATTACHED': {
        const why = d.reason === 'override' ? 'by the rule' : d.reason === 'team' ? "because the team's runbook condition matched" : d.reason === 'global' ? 'because a global runbook condition matched' : '';
        // Re-selected for an open alert after runbooks or the rule changed (RunbookResync): say so.
        const later = d.trigger ? `${d.previousRunbookId != null ? ', replacing the previous one,' : ''} after a runbook change` : '';
        return <>Runbook <b className="font-medium text-gray-900">{String(d.fileName ?? '')}</b> attached {why}{later}</>;
      }
      case 'RUNBOOK_DETACHED': return 'Runbook removed — it was deleted or no longer matches, and no other runbook applies';
      case 'ACK': return `Acknowledged by ${actorLabel(e.actor)}`;
      case 'RESOLVE': return `Resolved by ${actorLabel(e.actor)}`;
      case 'AUTO_CLOSE': return 'Resolved automatically: a recovery event matched the resolve condition';
      default: return e.actionType;
    }
  };

  const ack = async () => {
    if (!a) return;
    setAcking(true);
    try {
      const d = await api.ackAlert(a.id);
      upsertAlert(d); setDetail(d);
      toast({ title: 'Alert acknowledged', body: a.title, tone: 'ok', icon: Eye });
    } catch (e) {
      toast({ title: 'Could not acknowledge', body: errorText(e), tone: 'err' });
    } finally {
      setAcking(false);
    }
  };

  const createRule = (teamId?: number, tName?: string) => {
    if (!a || !detail) return;
    openRuleEditor(ruleFromEvent(detail.samplePayload, source, { alertId: a.id, teamId, teamName: tName }));
  };

  return (
    <>
      <div className={cx('overlay fixed inset-0 z-40 bg-gray-900/30', open && 'open')} onClick={closeAlert} />
      <aside className={cx('drawer fixed inset-y-0 right-0 z-50 w-full max-w-[640px] bg-white border-l border-gray-200 shadow-2xl flex flex-col', open && 'open')} aria-label="Alert details">
        {open && !a && (
          <div className="flex-1 grid place-items-center text-[13px] text-gray-500">
            {error ? <span className="text-red-600">{error}</span> : <span className="inline-flex items-center gap-2"><Loader className="w-4 h-4 spin" />Loading alert…</span>}
          </div>
        )}
        {open && a && (
          <>
            <div className="px-6 pt-5 pb-5 border-b border-gray-200">
              <div className="flex items-center gap-2">
                <SevPill s={a.severity} /><Status s={a.status} />
                <span className="text-xs text-gray-400">A-{a.id}</span>
                <button className="ml-auto btn btn-ghost btn-icon -mr-2" onClick={closeAlert} aria-label="Close"><X className="w-5 h-5" /></button>
              </div>
              <h2 className="mt-3 text-xl font-semibold text-gray-900 leading-snug">{a.title}</h2>
              <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1">
                <TeamName name={teamName(a.teamId)} />
                {a.ruleName && <span className="inline-flex items-center gap-1.5 text-[13px] text-gray-600"><Workflow className="w-3.5 h-3.5 text-gray-400" />{a.ruleName}</span>}
              </div>
              <div className="mt-4 flex flex-wrap gap-2">
                {operate && <button className="btn btn-white" onClick={ack} disabled={a.status !== 'OPEN' || acking}>
                  {acking ? <Loader className="w-4 h-4 spin" /> : <Eye className="w-4 h-4 text-amber-500" />}{a.status === 'ACKED' ? 'Acknowledged' : 'Acknowledge'}
                </button>}
                {operate && <button className="btn btn-dark" onClick={() => setResolving(true)} disabled={a.status === 'RESOLVED'}><Check className="w-4 h-4" />Resolve</button>}
                {!admin ? null : rule ? (
                  <button className="btn btn-ghost" onClick={() => openRuleEditor({ rule })}>Edit rule</button>
                ) : unrouted ? (
                  <button className="btn btn-white" disabled={!detail} onClick={() => createRule()}><Plus className="w-4 h-4" />Create Rule from Event</button>
                ) : null}
              </div>
            </div>

            <div className="flex-1 overflow-y-auto px-6 py-6 space-y-7">
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                <div><dt className="text-xs text-gray-500">Events</dt><dd className="mt-1 text-sm font-medium text-gray-900"><Occ a={a} /></dd></div>
                <div><dt className="text-xs text-gray-500">First seen</dt><dd className="mt-1 text-sm font-medium text-gray-900">{fmtDT(a.firstSeenAt)}</dd></div>
                <div><dt className="text-xs text-gray-500">Last seen</dt><dd className="mt-1 text-sm font-medium text-gray-900"><Ago t={a.lastSeenAt} /></dd></div>
                <div><dt className="text-xs text-gray-500">Grouping</dt><dd className="mt-1 text-sm font-medium text-gray-900">{rule ? groupingLabel(rule) : 'By source and shape'}</dd></div>
              </dl>

              {unrouted && a.status !== 'RESOLVED' && (
                <section>
                  <h3 className="text-sm font-semibold text-gray-900 mb-2">Who should own this?</h3>
                  <TeamSuggestions alertId={a.id} onCreateRule={s => createRule(s?.teamId, s?.teamName)} />
                </section>
              )}

              <section>
                <h3 className="text-sm font-semibold text-gray-900 mb-2">Runbook</h3>
                {rbId ? (
                  <div className="flex items-center gap-3 rounded-xl border border-gray-200 p-3">
                    <FileGlyph name={rbName ?? 'file'} />
                    <div className="min-w-0">
                      <div className="text-sm font-medium text-gray-900 truncate">{rbName ?? `Runbook #${rbId}`}</div>
                      <div className="text-[13px] text-gray-500">{rb ? (rb.teamId ? teamName(rb.teamId) : 'Global') : 'Attached runbook'}</div>
                    </div>
                    <a className="ml-auto btn btn-dark" href={detail?.runbook?.fileUrl ?? api.runbookFileUrl(rbId)} target="_blank" rel="noreferrer"
                       onClick={e => { e.preventDefault(); api.openRunbook(rbId).catch(err => toast({ title: 'Could not open the runbook', body: errorText(err), tone: 'err' })); }}><Download className="w-4 h-4" />Open</a>
                  </div>
                ) : (
                  <div className="rounded-xl border border-dashed border-gray-300 p-3 text-[13px] text-gray-500">
                    No runbook is attached. You can choose one in the rule, or upload a runbook with a matching condition.
                  </div>
                )}
              </section>

              <section>
                <div className="flex items-center mb-2">
                  <h3 className="text-sm font-semibold text-gray-900">Event payload</h3>
                  <span className="ml-2 text-xs text-gray-400">first event in this group</span>
                  {detail && (
                    <button className="ml-auto btn btn-ghost btn-sm" onClick={async () => toast(await copyText(JSON.stringify(detail.samplePayload, null, 2)) ? { title: 'Copied', tone: 'ok', icon: Copy } : { title: 'Clipboard not available here', tone: 'warn' })}>
                      <Copy className="w-3.5 h-3.5" />Copy
                    </button>
                  )}
                </div>
                {detail ? (
                  <>
                    <PayloadKV value={detail.samplePayload} />
                    <button type="button" className="mt-2 text-xs text-gray-400 hover:text-gray-600 underline underline-offset-2" onClick={() => setRaw(!raw)}>{raw ? 'Hide raw JSON' : 'View raw JSON'}</button>
                    {raw && <div className="mt-2"><JsonView value={detail.samplePayload} className="max-h-72" /></div>}
                  </>
                ) : <div className="text-[13px] text-gray-400">Loading…</div>}
              </section>

              {admin && <section>
                <h3 className="text-sm font-semibold text-gray-900 mb-2">Notifications</h3>
                {deliveries === null ? (
                  <div className="text-[13px] text-gray-400">Loading…</div>
                ) : deliveries.length === 0 ? (
                  <div className="text-[13px] text-gray-500">
                    Nobody was notified{unrouted ? ': the event did not match any team.' : ' yet. No team member may have a matching channel at this hour.'}
                  </div>
                ) : (
                  <div className="rounded-xl border border-gray-200 divide-y divide-gray-100">
                    {deliveries.slice(0, 20).map(d => (
                      <div key={d.id} className="flex items-center gap-2 px-3 py-2 text-[13px]">
                        <ChanBadge ch={d.channel} />
                        <span className="text-gray-700 truncate">{d.userId ? users.find(u => u.id === d.userId)?.name ?? `User #${d.userId}` : 'Shown in the app for the team'}</span>
                        <span className="ml-auto flex items-center gap-2">
                          {d.retryCount > 0 && <span className="text-xs text-gray-400">{d.retryCount} {d.retryCount === 1 ? 'retry' : 'retries'}</span>}
                          <OutPill s={d.status} />
                        </span>
                      </div>
                    ))}
                  </div>
                )}
              </section>}

              <section>
                <h3 className="text-sm font-semibold text-gray-900 mb-3">Audit log</h3>
                {audit.length === 0 ? <div className="text-[13px] text-gray-400">{detail ? 'No entries yet.' : 'Loading…'}</div> : (
                  <ol className="relative space-y-4 before:absolute before:left-[15px] before:top-2 before:bottom-2 before:w-px before:bg-gray-200">
                    {audit.map(e => {
                      const [Ic, c] = AUDIT[e.actionType] ?? [CircleCheck, 'bg-gray-100 text-gray-500'];
                      return (
                        <li key={e.id} className="relative flex gap-3">
                          <span className={`relative z-10 h-8 w-8 shrink-0 rounded-full grid place-items-center ring-4 ring-white ${c}`}><Ic className="w-4 h-4" /></span>
                          <div className="min-w-0 pt-1">
                            <div className="flex flex-wrap items-center gap-x-2 gap-y-1"><code className="code !text-[11px]">{e.actionType}</code><span className="text-xs text-gray-400">{fmtDT(e.createdAt)}</span></div>
                            <div className="mt-1 text-[13px] text-gray-600">{auditText(e)}</div>
                          </div>
                        </li>
                      );
                    })}
                  </ol>
                )}
              </section>
            </div>
          </>
        )}
      </aside>
      <ResolveModal alert={resolving ? a : null} onClose={() => setResolving(false)} />
    </>
  );
}
