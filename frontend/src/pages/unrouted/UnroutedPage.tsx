/**
 * Events no team matched (UNROUTED alerts, grouped by source and event shape). Expanding one shows its sample event
 * and the team suggestions (`GET /api/alerts/{id}/team-suggestions`); "Rule" prefills a rule for that team.
 */
import { useEffect, useMemo, useState } from 'react';
import { ChevronRight, CircleCheck, Copy, ExternalLink, Plus } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { Ago, Empty, JsonView, PageIntro, PageWrap, SevPill, Spinner, cx } from '../../components/ui';
import { copyText, fmtDT, unroutedSource } from '../../utils/format';
import { ruleFromEvent } from '../../utils/ruleFromEvent';
import { Occ } from '../feed/AlertsPage';
import { TeamSuggestions } from '../alert/TeamSuggestions';
import type { AlertDetail, AlertSummary } from '../../types';

function UnroutedItem({ a, open, onToggle }: { a: AlertSummary; open: boolean; onToggle: () => void }) {
  const { openAlert, openRuleEditor, fresh } = useStore();
  const toast = useToast();
  const [detail, setDetail] = useState<AlertDetail | null>(null);
  const [err, setErr] = useState('');
  const src = unroutedSource(a.title);

  useEffect(() => {
    if (!open || detail) return;
    api.getAlert(a.id).then(setDetail).catch(e => setErr(errorText(e)));
  }, [open, detail, a.id]);

  const keys = detail && detail.samplePayload && typeof detail.samplePayload === 'object' ? Object.keys(detail.samplePayload as object) : [];
  const isNew = Date.now() - (fresh[a.id] ?? 0) < 2500;

  return (
    <div className={cx(isNew && 'fade-in bg-amber-50/50')}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3 hover:bg-gray-50 cursor-pointer" onClick={onToggle}>
        <span className={cx('text-gray-400 transition-transform', open && 'rotate-90')}><ChevronRight className="w-4 h-4" /></span>
        <SevPill s="UNKNOWN" />
        <span className="text-sm font-medium text-gray-900">{src ?? a.title}</span>
        <Occ a={a} />
        {keys.length > 0 && (
          <span className="text-[13px] text-gray-500 truncate max-w-[340px]">
            Fields: {keys.slice(0, 4).map((k, i) => <span key={k}>{i > 0 && ', '}<span className="font-mono text-[12px]">{k}</span></span>)}{keys.length > 4 && ` and ${keys.length - 4} more`}
          </span>
        )}
        <span className="ml-auto flex items-center gap-3" onClick={e => e.stopPropagation()}>
          <span className="text-[13px] text-gray-500 whitespace-nowrap"><Ago t={a.lastSeenAt} /></span>
          <button className="btn btn-white btn-sm" onClick={() => openAlert(a.id)}><ExternalLink className="w-3.5 h-3.5" />Open</button>
        </span>
      </div>
      {open && (
        <div className="px-4 pb-4 pl-11 fade-in space-y-3">
          {err && <div className="text-[13px] text-red-600">{err}</div>}
          {!detail && !err && <div className="text-[13px] text-gray-400">Loading…</div>}
          {detail && (
            <>
              <TeamSuggestions
                alertId={a.id}
                onCreateRule={s => openRuleEditor(ruleFromEvent(detail.samplePayload, src, { alertId: a.id, teamId: s?.teamId, teamName: s?.teamName }))}
              />
              <div>
                <div className="flex items-center gap-3 mb-2 text-xs text-gray-400">
                  <span>First seen {fmtDT(a.firstSeenAt)}</span><span>Alert A-{a.id}</span>
                  <button className="ml-auto btn btn-ghost btn-sm" onClick={async () => toast(await copyText(JSON.stringify(detail.samplePayload, null, 2)) ? { title: 'Copied', tone: 'ok' } : { title: 'Clipboard not available here', tone: 'warn' })}>
                    <Copy className="w-3.5 h-3.5" />Copy
                  </button>
                  <button className="btn btn-white btn-sm" onClick={() => openRuleEditor(ruleFromEvent(detail.samplePayload, src, { alertId: a.id }))}>
                    <Plus className="w-3.5 h-3.5" />Create Rule from Event
                  </button>
                </div>
                <JsonView value={detail.samplePayload} className="max-h-64" />
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

export function UnroutedPage() {
  const { alerts, alertsLoaded } = useStore();
  const [source, setSource] = useState('all');
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const unrouted = useMemo(
    () => alerts.filter(a => a.teamId == null && a.status !== 'RESOLVED').sort((x, y) => y.lastSeenAt.localeCompare(x.lastSeenAt)),
    [alerts],
  );
  const counts = useMemo(() => {
    const m = new Map<string, number>();
    unrouted.forEach(a => { const s = unroutedSource(a.title) ?? 'unknown'; m.set(s, (m.get(s) ?? 0) + 1); });
    return [...m].sort((a, b) => b[1] - a[1]);
  }, [unrouted]);
  const list = unrouted.filter(a => source === 'all' || (unroutedSource(a.title) ?? 'unknown') === source);

  const toggle = (id: number) => setExpanded(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  return (
    <PageWrap>
      <PageIntro>
        These events did not match any team, so nobody was notified. Similar events are grouped into one entry. Expand one to see which team it
        probably belongs to, then create a rule from it — or widen a team's routing in Teams &amp; Users.
      </PageIntro>
      <div className="flex flex-wrap gap-1.5 mb-4">
        <button className={cx('pill', source === 'all' && 'on')} onClick={() => setSource('all')}>All sources <span className="opacity-60">{unrouted.length}</span></button>
        {counts.map(([s, n]) => (
          <button key={s} className={cx('pill', source === s && 'on')} onClick={() => setSource(s)}>{s} <span className="opacity-60">{n}</span></button>
        ))}
      </div>
      <div className="card divide-y divide-gray-100 overflow-hidden">
        {!alertsLoaded ? <Spinner /> : list.length ? list.map(a => (
          <UnroutedItem key={a.id} a={a} open={expanded.has(a.id)} onToggle={() => toggle(a.id)} />
        )) : <Empty icon={CircleCheck} title="Every event found a team" />}
      </div>
    </PageWrap>
  );
}
