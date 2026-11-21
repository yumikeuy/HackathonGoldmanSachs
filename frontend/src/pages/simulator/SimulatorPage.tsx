/**
 * Delivery simulator: switch channels down/up (messages are retried and delivered when they come back),
 * watch every delivery attempt live (`/topic/simulator`), consumer lag, and send test events.
 */
import { useCallback, useEffect, useState } from 'react';
import { Activity, Inbox, Loader, Zap } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { Ago, CHAN, CHANNELS, ChanBadge, Empty, OutPill, PageIntro, PageWrap, Spinner, Toggle, cx } from '../../components/ui';
import { fmtNum } from '../../utils/format';
import { GenerateModal } from '../sources/GenerateModal';
import type { Channel, ChannelState, Delivery, IngestLag } from '../../types';

function payloadText(d: Delivery): string {
  const p = (d.payload ?? {}) as Record<string, unknown>;
  return String(p.subject ?? p.text ?? p.title ?? JSON.stringify(p)).slice(0, 140);
}

export function SimulatorPage() {
  const { users, onDelivery, openAlert, sources } = useStore();
  const toast = useToast();
  const [channels, setChannels] = useState<ChannelState[] | null>(null);
  const [toggling, setToggling] = useState<Channel | null>(null);
  const [lag, setLag] = useState<IngestLag | null>(null);
  const [items, setItems] = useState<Delivery[] | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [gen, setGen] = useState(false);

  useEffect(() => {
    api.getChannels().then(setChannels).catch(e => { setChannels([]); toast({ title: 'Could not load channels', body: errorText(e), tone: 'err' }); });
    api.getDeliveries(null, 50).then(p => { setItems(p.items); setCursor(p.nextCursor ?? null); }).catch(() => setItems([]));
  }, [toast]);

  useEffect(() => {
    const poll = () => api.getLag().then(setLag).catch(() => setLag(null));
    poll();
    const iv = setInterval(poll, 5000);
    return () => clearInterval(iv);
  }, []);

  useEffect(() => onDelivery(d => setItems(xs => {
    const list = xs ?? [];
    const i = list.findIndex(x => x.id === d.id);
    if (i < 0) return [d, ...list].slice(0, 500);
    const n = list.slice(); n[i] = d; return n;
  })), [onDelivery]);

  const more = useCallback(async () => {
    if (!cursor) return;
    setLoadingMore(true);
    try {
      const p = await api.getDeliveries(cursor, 50);
      setItems(xs => [...(xs ?? []), ...p.items.filter(d => !(xs ?? []).some(x => x.id === d.id))]);
      setCursor(p.nextCursor ?? null);
    } finally {
      setLoadingMore(false);
    }
  }, [cursor]);

  const toggle = async (c: Channel) => {
    setToggling(c);
    try {
      const s = await api.toggleChannel(c);
      setChannels(cs => (cs ?? []).map(x => (x.channel === c ? s : x)));
      toast({ title: `${CHAN[c].l} is ${s.enabled ? 'up' : 'down'}`, body: s.enabled ? 'Queued messages are delivered now.' : 'New messages are retried (2/5/15/60 s) until it comes back.', tone: s.enabled ? 'ok' : 'warn' });
    } catch (e) {
      toast({ title: 'Could not switch the channel', body: errorText(e), tone: 'err' });
    } finally {
      setToggling(null);
    }
  };

  const counts = (items ?? []).reduce((m, d) => { m[d.status] = (m[d.status] ?? 0) + 1; return m; }, {} as Record<string, number>);

  return (
    <PageWrap wide>
      <PageIntro right={<button className="btn btn-dark" onClick={() => setGen(true)} disabled={!sources.length}><Zap className="w-4 h-4" />Send test events</button>}>
        SMS and phone calls are simulated; email goes to MailHog. Switch a channel down to simulate a provider outage — nothing is lost, messages are retried.
      </PageIntro>

      <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
        {CHANNELS.map(c => {
          const s = channels?.find(x => x.channel === c);
          const Ic = CHAN[c].i;
          return (
            <div key={c} className="card p-4">
              <div className="flex items-center gap-2">
                <span className="h-8 w-8 rounded-lg grid place-items-center bg-gray-100 text-gray-600"><Ic className="w-4 h-4" /></span>
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-gray-900">{CHAN[c].l}</div>
                  <div className={cx('text-xs', !s ? 'text-gray-400' : s.enabled ? 'text-emerald-600' : 'text-red-600')}>{!s ? '—' : s.enabled ? 'Up' : 'Down'}</div>
                </div>
                <div className="ml-auto">{toggling === c ? <Loader className="w-4 h-4 spin text-gray-400" /> : s && <Toggle on={s.enabled} onChange={() => toggle(c)} label={`${CHAN[c].l} up`} />}</div>
              </div>
              <div className="mt-3 text-xs text-gray-500">{CHAN[c].desc}</div>
            </div>
          );
        })}
        <div className="card p-4">
          <div className="flex items-center gap-2">
            <span className="h-8 w-8 rounded-lg grid place-items-center bg-gray-100 text-gray-600"><Activity className="w-4 h-4" /></span>
            <div><div className="text-sm font-semibold text-gray-900">Processing lag</div><div className="text-xs text-gray-500">{lag?.groupId ?? 'consumer group'}</div></div>
          </div>
          <div className="mt-2 text-2xl font-semibold tabular-nums text-gray-900">{lag ? fmtNum(lag.totalLag) : '—'} <span className="text-sm font-normal text-gray-500">events behind</span></div>
        </div>
      </div>

      <div className="card overflow-hidden mt-6">
        <div className="flex flex-wrap items-center gap-3 px-5 py-3 border-b border-gray-200">
          <span className="text-sm font-semibold text-gray-900">Delivery attempts</span>
          <span className="text-[13px] text-gray-500">live · newest first</span>
          <div className="ml-auto flex gap-3 text-[13px] text-gray-500">
            <span>{fmtNum(counts.SENT ?? 0)} sent</span><span>{fmtNum(counts.PENDING ?? 0)} pending</span><span>{fmtNum(counts.FAILED ?? 0)} failed</span>
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr><th className="th pl-5">Channel</th><th className="th">Recipient</th><th className="th">Message</th><th className="th">Alert</th><th className="th">Status</th><th className="th">Retries</th><th className="th pr-5">Created</th></tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {items === null ? <tr><td colSpan={7}><Spinner /></td></tr> : items.length === 0 ? (
                <tr><td colSpan={7}><Empty icon={Inbox} title="No deliveries yet" body="Send test events — alerts notify their team's users here." /></td></tr>
              ) : items.map(d => (
                <tr key={d.id} className="hover:bg-gray-50">
                  <td className="td pl-5"><ChanBadge ch={d.channel} /></td>
                  <td className="td text-[13px] text-gray-700 whitespace-nowrap">{d.userId ? users.find(u => u.id === d.userId)?.name ?? `User #${d.userId}` : <span className="text-gray-400">Team feed</span>}</td>
                  <td className="td text-[13px] text-gray-600 max-w-[420px]">
                    <div className="truncate" title={payloadText(d)}>{payloadText(d)}</div>
                    {d.lastError && <div className="text-xs text-red-600 truncate" title={d.lastError}>{d.lastError}</div>}
                  </td>
                  <td className="td"><button className="text-[13px] text-gray-700 underline underline-offset-2 hover:text-gray-900" onClick={() => openAlert(d.alertId)}>A-{d.alertId}</button></td>
                  <td className="td"><OutPill s={d.status} /></td>
                  <td className="td text-[13px] text-gray-600">{d.retryCount}</td>
                  <td className="td pr-5 text-[13px] text-gray-500 whitespace-nowrap"><Ago t={d.createdAt} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {cursor && (
          <div className="px-5 py-3 border-t border-gray-200 text-center">
            <button className="btn btn-white btn-sm" onClick={more} disabled={loadingMore}>{loadingMore && <Loader className="w-3 h-3 spin" />}Load more</button>
          </div>
        )}
      </div>
      <GenerateModal open={gen} onClose={() => setGen(false)} />
    </PageWrap>
  );
}
