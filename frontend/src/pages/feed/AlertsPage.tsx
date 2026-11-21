/** Alert feed of all teams (plus UNROUTED), live; filters, sort and client-side pages as in the prototype. */
import { useEffect, useMemo, useState } from 'react';
import { BellOff, ChevronLeft, ChevronRight, Loader, Paperclip } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { Ago, Empty, PageWrap, SEV, SEV_RANK, Search, Sev, SevBars, Status, Spinner, cx } from '../../components/ui';
import { fmtNum } from '../../utils/format';
import { ResolveModal } from '../alert/ResolveModal';
import type { AlertSeverity, AlertSummary } from '../../types';
import { usePermissions } from '../../auth/permissions';

const PAGE_SIZE = 15;
type StatusFilter = 'active' | 'OPEN' | 'ACKED' | 'RESOLVED' | 'all';
type Sort = 'severity' | 'last' | 'occ';
const ST_RANK = { OPEN: 0, ACKED: 1, RESOLVED: 2 } as const;

/** Occurrence counter that bumps when it grows. */
export function Occ({ a }: { a: AlertSummary }) {
  const { bumps } = useStore();
  const at = bumps[a.id] ?? 0;
  return <span key={at} className={cx('occ', Date.now() - at < 600 && 'bump')}>×{fmtNum(a.occurrencesCount)}</span>;
}

export function AlertsPage() {
  const { alerts, runbooks, alertsLoaded, resolvedLoaded, loadResolved, teamName, openAlert, openAlertId, upsertAlert, fresh } = useStore();
  const toast = useToast();
  const { operate } = usePermissions();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<StatusFilter>('active');
  const [sev, setSev] = useState<Set<AlertSeverity>>(new Set());
  const [sort, setSort] = useState<Sort>('severity');
  const [page, setPage] = useState(1);
  const [resolving, setResolving] = useState<AlertSummary | null>(null);
  const [acking, setAcking] = useState<number | null>(null);

  useEffect(() => {
    if ((status === 'RESOLVED' || status === 'all') && !resolvedLoaded) loadResolved();
  }, [status, resolvedLoaded, loadResolved]);

  const list = useMemo(() => {
    const s = q.trim().toLowerCase();
    return alerts
      .filter(a => {
        if (status === 'active' && a.status === 'RESOLVED') return false;
        if (status !== 'active' && status !== 'all' && a.status !== status) return false;
        if (sev.size && !sev.has(a.severity)) return false;
        if (s && !`${a.title} ${teamName(a.teamId) ?? ''} ${a.ruleName ?? ''}`.toLowerCase().includes(s)) return false;
        return true;
      })
      .sort((a, b) =>
        sort === 'last' ? b.lastSeenAt.localeCompare(a.lastSeenAt)
          : sort === 'occ' ? b.occurrencesCount - a.occurrencesCount
            : ST_RANK[a.status] - ST_RANK[b.status] || SEV_RANK[a.severity] - SEV_RANK[b.severity] || b.lastSeenAt.localeCompare(a.lastSeenAt));
  }, [alerts, q, status, sev, sort, teamName]);

  const pages = Math.max(1, Math.ceil(list.length / PAGE_SIZE));
  const p = Math.min(page, pages);
  const slice = list.slice((p - 1) * PAGE_SIZE, p * PAGE_SIZE);
  const events = list.reduce((s, a) => s + a.occurrencesCount, 0);

  const ack = async (a: AlertSummary) => {
    setAcking(a.id);
    try {
      upsertAlert(await api.ackAlert(a.id));
      toast({ title: 'Alert acknowledged', body: a.title, tone: 'ok' });
    } catch (e) {
      toast({ title: 'Could not acknowledge', body: errorText(e), tone: 'err' });
    } finally {
      setAcking(null);
    }
  };

  const toggleSev = (s: AlertSeverity) => {
    const n = new Set(sev);
    n.has(s) ? n.delete(s) : n.add(s);
    setSev(n);
    setPage(1);
  };

  return (
    <PageWrap wide>
      <div className="card overflow-hidden">
        <div className="flex flex-wrap items-center gap-2 px-4 py-3 border-b border-gray-200">
          <Search value={q} onChange={v => { setQ(v); setPage(1); }} placeholder="Search alerts…" className="w-full sm:w-60" />
          <div className="seg">
            {([['active', 'Active'], ['OPEN', 'Open'], ['ACKED', 'Acked'], ['RESOLVED', 'Resolved'], ['all', 'All']] as Array<[StatusFilter, string]>).map(([k, l]) => (
              <button key={k} className={cx(status === k && 'on')} onClick={() => { setStatus(k); setPage(1); }}>{l}</button>
            ))}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'] as AlertSeverity[]).map(s => (
              <button key={s} className={cx('pill', sev.has(s) && 'on')} onClick={() => toggleSev(s)}><SevBars s={s} />{SEV[s].l}</button>
            ))}
          </div>
          <select className="inp ml-auto" value={sort} onChange={e => setSort(e.target.value as Sort)}>
            <option value="severity">Sort by severity</option>
            <option value="last">Sort by last seen</option>
            <option value="occ">Sort by events</option>
          </select>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="th pl-5">Alert</th><th className="th">Severity</th><th className="th">Status</th><th className="th">Team</th>
                <th className="th">Events</th><th className="th">Runbook</th><th className="th">Last seen</th><th className="th pr-5" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {!alertsLoaded ? (
                <tr><td colSpan={8}><Spinner label="Loading alerts…" /></td></tr>
              ) : slice.length ? slice.map(a => {
                const resolved = a.status === 'RESOLVED';
                const isNew = Date.now() - (fresh[a.id] ?? 0) < 2600;
                return (
                  <tr key={a.id} onClick={() => openAlert(a.id)} className={cx(isNew && 'row-in', 'hover:bg-gray-50 cursor-pointer', resolved && 'text-gray-400', openAlertId === a.id && 'bg-gray-50')}>
                    <td className="td pl-5 min-w-[300px]">
                      <div className={cx('font-medium truncate max-w-[440px]', resolved ? 'text-gray-500' : 'text-gray-900')}>{a.title}</div>
                      <div className="text-[13px] text-gray-500 truncate max-w-[440px]">{a.teamId == null ? 'No team matched' : a.ruleName ?? 'Rule deleted'} · A-{a.id}</div>
                    </td>
                    <td className="td"><Sev s={a.severity} /></td>
                    <td className="td"><Status s={a.status} /></td>
                    <td className="td whitespace-nowrap text-[13px] text-gray-600">{teamName(a.teamId) ?? <span className="text-gray-400">—</span>}</td>
                    <td className="td"><Occ a={a} /></td>
                    <td className="td">
                      {a.runbookId ? (
                        <a className="btn btn-ghost btn-sm" href={api.runbookFileUrl(a.runbookId)} target="_blank" rel="noreferrer" title="Open the runbook"
                           onClick={e => { e.stopPropagation(); e.preventDefault(); api.openRunbook(a.runbookId!).catch(err => toast({ title: 'Could not open the runbook', body: errorText(err), tone: 'err' })); }}>
                          <Paperclip className="w-3.5 h-3.5" /><span className="max-w-[120px] truncate">{runbooks.find(r => r.id === a.runbookId)?.fileName ?? 'Runbook'}</span>
                        </a>
                      ) : <span className="text-gray-300">—</span>}
                    </td>
                    <td className="td whitespace-nowrap text-[13px] text-gray-500"><Ago t={a.lastSeenAt} /></td>
                    <td className="td pr-5">
                      <div className="flex justify-end gap-1.5" onClick={e => e.stopPropagation()}>
                        {operate && a.status === 'OPEN' && (
                          <button className="btn btn-white btn-sm" disabled={acking === a.id} onClick={() => ack(a)}>{acking === a.id && <Loader className="w-3 h-3 spin" />}Ack</button>
                        )}
                        {operate && !resolved && <button className="btn btn-white btn-sm" onClick={() => setResolving(a)}>Resolve</button>}
                      </div>
                    </td>
                  </tr>
                );
              }) : (
                <tr><td colSpan={8}><Empty icon={BellOff} title="No alerts match these filters" body="Try clearing the search or filters, or send events from the Simulator page." /></td></tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="flex flex-wrap items-center gap-3 px-5 py-3 border-t border-gray-200 text-[13px] text-gray-500">
          <span>{list.length} alert{list.length === 1 ? '' : 's'} grouping {fmtNum(events)} events</span>
          <div className="ml-auto flex items-center gap-2">
            <span>Page {p}{pages > 1 ? ` of ${pages}` : ''}</span>
            <button className="btn btn-white btn-sm w-7 px-0" disabled={p <= 1} onClick={() => setPage(p - 1)} aria-label="Previous page"><ChevronLeft className="w-4 h-4" /></button>
            <button className="btn btn-white btn-sm w-7 px-0" disabled={p >= pages} onClick={() => setPage(p + 1)} aria-label="Next page"><ChevronRight className="w-4 h-4" /></button>
          </div>
        </div>
      </div>
      <ResolveModal alert={resolving} onClose={() => setResolving(null)} />
    </PageWrap>
  );
}
