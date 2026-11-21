/** Events whose processing failed after retries (dead-letter topic); replay sends one through processing again. */
import { useCallback, useEffect, useState } from 'react';
import { ChevronRight, CircleCheck, CircleX, Loader, RotateCcw } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { Ago, Empty, JsonView, PageIntro, PageWrap, Spinner, cx } from '../../components/ui';
import { fmtDT } from '../../utils/format';
import type { FailedEvent } from '../../types';

function friendlyError(err: string | null | undefined): string {
  const e = err ?? '';
  if (/statement timeout/i.test(e)) return 'The database took too long to respond';
  if (/lock_timeout|LockException/i.test(e)) return 'The alert was busy being updated by another event';
  if (/NumberFormat/i.test(e)) return 'A number in the event could not be read';
  if (/TooLarge/i.test(e)) return 'The event is bigger than the 1 MB limit';
  if (/duplicate key/i.test(e)) return 'A matching alert was being created at the same moment';
  if (/Json|Parse/i.test(e)) return 'The event could not be parsed';
  if (/ClassCast/i.test(e)) return 'A field had an unexpected shape';
  return 'The event could not be processed';
}

export function FailedPage() {
  const { refreshFailed } = useStore();
  const toast = useToast();
  const [items, setItems] = useState<FailedEvent[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [replaying, setReplaying] = useState<Set<string>>(new Set());

  const load = useCallback(async () => {
    try {
      const p = await api.getFailedEvents(null, 100);
      setItems(p.items);
      setCursor(p.nextCursor ?? null);
    } catch (e) {
      setItems([]);
      toast({ title: 'Could not load failed events', body: errorText(e), tone: 'err' });
    }
  }, [toast]);
  useEffect(() => { load(); }, [load]);

  const more = async () => {
    if (!cursor) return;
    const p = await api.getFailedEvents(cursor, 100);
    setItems(xs => [...(xs ?? []), ...p.items]);
    setCursor(p.nextCursor ?? null);
  };

  const replay = async (e: FailedEvent) => {
    setReplaying(s => new Set(s).add(e.eventId));
    try {
      await api.replayEvent(e.eventId);
      setItems(xs => (xs ?? []).filter(x => x.eventId !== e.eventId));
      toast({ title: 'Event replayed', body: `Sent through processing again (from ${e.sourceName}). If it fails again it comes back here.`, tone: 'ok' });
      refreshFailed();
    } catch (err) {
      toast({ title: 'Replay failed', body: errorText(err), tone: 'err' });
    } finally {
      setReplaying(s => { const n = new Set(s); n.delete(e.eventId); return n; });
    }
  };

  const toggle = (id: string) => setExpanded(s => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  return (
    <PageWrap>
      <PageIntro right={items && items.length > 0 && (
        <button className="btn btn-white" onClick={() => items.forEach((e, i) => setTimeout(() => replay(e), i * 150))}><RotateCcw className="w-4 h-4" />Replay all</button>
      )}>
        These events arrived but could not be processed, even after automatic retries. Replay sends an event through processing again once the cause is fixed.
      </PageIntro>
      <div className="card divide-y divide-gray-100 overflow-hidden">
        {items === null ? <Spinner /> : items.length === 0 ? <Empty icon={CircleCheck} title="No failed events" body="Everything that arrived was processed." /> : items.map(e => {
          const open = expanded.has(e.eventId);
          const busy = replaying.has(e.eventId);
          return (
            <div key={e.eventId}>
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3.5 hover:bg-gray-50 cursor-pointer" onClick={() => toggle(e.eventId)}>
                <span className={cx('text-gray-400 transition-transform', open && 'rotate-90')}><ChevronRight className="w-4 h-4" /></span>
                <span className="h-8 w-8 shrink-0 rounded-full grid place-items-center bg-red-50 text-red-600"><CircleX className="w-4 h-4" /></span>
                <div className="min-w-0 flex-1">
                  <div className="text-sm text-gray-900"><b className="font-medium">{friendlyError(e.error)}</b> <span className="text-gray-500">· from {e.sourceName}</span></div>
                  <div className="mt-0.5 font-mono text-[12px] text-gray-500 truncate max-w-[640px]">{JSON.stringify(e.rawPayload)}</div>
                </div>
                <span className="text-[13px] text-gray-500 whitespace-nowrap"><Ago t={e.ingestedAt} /></span>
                <button className="btn btn-dark btn-sm" disabled={busy} onClick={ev => { ev.stopPropagation(); replay(e); }}>
                  {busy ? <Loader className="w-3.5 h-3.5 spin" /> : <RotateCcw className="w-3.5 h-3.5" />}{busy ? 'Replaying' : 'Replay'}
                </button>
              </div>
              {open && (
                <div className="px-4 pb-4 pl-[60px] fade-in space-y-2">
                  {e.error && (
                    <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-[13px] text-red-800">
                      <div className="text-xs font-medium text-red-700 mb-0.5">Error details</div>
                      <span className="font-mono text-[12px] break-all">{e.error}</span>
                    </div>
                  )}
                  <JsonView value={e.rawPayload} className="max-h-64" />
                  <div className="text-xs text-gray-400">Received {fmtDT(e.ingestedAt)} · event {e.eventId}</div>
                </div>
              )}
            </div>
          );
        })}
      </div>
      {cursor && <div className="mt-4 text-center"><button className="btn btn-white btn-sm" onClick={more}>Load more</button></div>}
    </PageWrap>
  );
}
