/** Design-system components ported from the prototype (Sev, Status, Toggle, Empty, JsonView, Modal, …). */
import { useEffect, useState, type ReactNode } from 'react';
import {
  CircleCheck, CircleDot, Clock, Eye, File, FileCode, FileText, FileType, Globe, Loader, Mail, MessageSquare,
  Monitor, Phone, Search as SearchIcon, Users, X, type LucideIcon,
} from 'lucide-react';
import type { AlertSeverity, AlertStatus, Channel } from '../types';
import { ago, fmtDT, fmtNum, flattenKV, highlightJSON, kvLabel } from '../utils/format';
import { OPLABEL, children, isAnd, isLeaf } from '../utils/condition';
import type { Condition } from '../types';

export const cx = (...xs: Array<string | false | null | undefined>) => xs.filter(Boolean).join(' ');

/* ---------------- severity / status / channel ---------------- */

export const SEV: Record<AlertSeverity, { l: string; lvl: number; text: string; bar: string; pill: string }> = {
  CRITICAL: { l: 'Critical', lvl: 4, text: 'text-red-600', bar: '#EF4444', pill: 'bg-red-50 text-red-700 ring-red-200' },
  HIGH: { l: 'High', lvl: 3, text: 'text-amber-600', bar: '#F59E0B', pill: 'bg-amber-50 text-amber-800 ring-amber-200' },
  MEDIUM: { l: 'Medium', lvl: 2, text: 'text-amber-600', bar: '#F59E0B', pill: 'bg-yellow-50 text-yellow-800 ring-yellow-200' },
  LOW: { l: 'Low', lvl: 1, text: 'text-blue-600', bar: '#3B82F6', pill: 'bg-blue-50 text-blue-700 ring-blue-200' },
  UNKNOWN: { l: 'Unknown', lvl: 0, text: 'text-gray-500', bar: '#9CA3AF', pill: 'bg-gray-100 text-gray-600 ring-gray-200' },
};
export const SEV_RANK: Record<AlertSeverity, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, UNKNOWN: 4 };

export function SevBars({ s }: { s: AlertSeverity }) {
  return (
    <svg width="14" height="12" viewBox="0 0 14 12" aria-hidden="true" className="shrink-0">
      {[0, 1, 2, 3].map(i => (
        <rect key={i} x={i * 3.7} y={9 - i * 3} width="2.4" height={3 + i * 3} rx=".6" fill={i < SEV[s].lvl ? SEV[s].bar : '#D1D5DB'} />
      ))}
    </svg>
  );
}
export const Sev = ({ s }: { s: AlertSeverity }) => (
  <span className={`inline-flex items-center gap-1.5 text-[13px] font-medium ${SEV[s].text}`}><SevBars s={s} />{SEV[s].l}</span>
);
export const SevPill = ({ s }: { s: AlertSeverity }) => (
  <span className={`inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${SEV[s].pill}`}><SevBars s={s} />{SEV[s].l}</span>
);

const STATUS: Record<AlertStatus, { l: string; i: LucideIcon; c: string }> = {
  OPEN: { l: 'Open', i: CircleDot, c: 'text-red-500' },
  ACKED: { l: 'Acked', i: Eye, c: 'text-amber-500' },
  RESOLVED: { l: 'Resolved', i: CircleCheck, c: 'text-emerald-500' },
};
export function Status({ s }: { s: AlertStatus }) {
  const { l, i: Ic, c } = STATUS[s];
  return <span className="inline-flex items-center gap-1.5 text-[13px] text-gray-600"><Ic className={`w-3.5 h-3.5 ${c}`} />{l}</span>;
}

export const CHAN: Record<Channel, { l: string; i: LucideIcon; desc: string }> = {
  EMAIL: { l: 'Email', i: Mail, desc: 'SMTP → MailHog (localhost:8025)' },
  SMS: { l: 'SMS', i: MessageSquare, desc: 'Simulated SMS gateway' },
  CALL: { l: 'Phone Call', i: Phone, desc: 'Simulated voice provider' },
  UI: { l: 'UI', i: Monitor, desc: 'WebSocket push to the live feed' },
};
export const CHANNELS: Channel[] = ['EMAIL', 'SMS', 'CALL', 'UI'];

export function ChanBadge({ ch, extra, dim }: { ch: Channel; extra?: string; dim?: boolean }) {
  const Ic = CHAN[ch].i;
  return (
    <span className={cx('inline-flex items-center gap-1 rounded-md bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-700 whitespace-nowrap', dim && 'opacity-50')}>
      <Ic className="w-3 h-3 text-gray-500" />{CHAN[ch].l}{extra && <span className="font-normal text-gray-500"> {extra}</span>}
    </span>
  );
}

const OUT = { PENDING: ['Pending', 'bg-amber-50 text-amber-700 ring-amber-200'], SENT: ['Sent', 'bg-emerald-50 text-emerald-700 ring-emerald-200'], FAILED: ['Failed', 'bg-red-50 text-red-700 ring-red-200'] } as const;
export const OutPill = ({ s }: { s: keyof typeof OUT }) => (
  <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${OUT[s][1]}`}>
    {s === 'PENDING' && <Loader className="w-3 h-3 spin" />}{OUT[s][0]}
  </span>
);

/* ---------------- controls ---------------- */

export function Toggle({ on, onChange, label, disabled }: { on: boolean; onChange: () => void; label: string; disabled?: boolean }) {
  return (
    <button
      type="button" role="switch" aria-checked={on} aria-label={label} disabled={disabled}
      onClick={e => { e.stopPropagation(); onChange(); }}
      className={cx('relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors disabled:opacity-50', on ? 'bg-gray-900' : 'bg-gray-200')}
    >
      <span className={cx('inline-block h-4 w-4 rounded-full bg-white shadow transition-transform', on ? 'translate-x-[18px]' : 'translate-x-0.5')} />
    </button>
  );
}

export function Seg<T extends string>({ value, options, onChange, className }: { value: T; options: Array<[T, ReactNode]>; onChange: (v: T) => void; className?: string }) {
  return (
    <div className={cx('seg', className)}>
      {options.map(([k, l]) => (
        <button key={k} type="button" className={cx(value === k && 'on', 'inline-flex items-center gap-1.5')} onClick={() => onChange(k)}>{l}</button>
      ))}
    </div>
  );
}

export function Search({ value, onChange, placeholder, className = 'w-64' }: { value: string; onChange: (v: string) => void; placeholder: string; className?: string }) {
  return (
    <div className="relative">
      <SearchIcon className="w-4 h-4 text-gray-400 absolute left-3 top-1/2 -translate-y-1/2" />
      <input className={cx('inp pl-9', className)} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} />
    </div>
  );
}

/* ---------------- layout ---------------- */

export const PageWrap = ({ children, wide }: { children: ReactNode; wide?: boolean }) => (
  <div className={cx(wide ? 'max-w-[1400px]' : 'max-w-[1200px]', 'mx-auto px-4 md:px-8 py-6 md:py-8')}>{children}</div>
);

export const PageIntro = ({ children, right }: { children: ReactNode; right?: ReactNode }) => (
  <div className="flex flex-wrap items-center gap-3 mb-5">
    <p className="text-sm text-gray-500 max-w-2xl">{children}</p>
    <div className="ml-auto flex flex-wrap items-center gap-2">{right}</div>
  </div>
);

export function Empty({ icon: Ic, title, body }: { icon: LucideIcon; title: string; body?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center text-center py-14 px-6">
      <div className="h-11 w-11 rounded-full bg-gray-100 grid place-items-center mb-3"><Ic className="w-5 h-5 text-gray-500" /></div>
      <div className="text-[15px] font-medium text-gray-900">{title}</div>
      {body && <div className="mt-1 text-sm text-gray-500 max-w-sm">{body}</div>}
    </div>
  );
}

export const Spinner = ({ label = 'Loading…' }: { label?: string }) => (
  <div className="flex items-center justify-center gap-2 py-14 text-[13px] text-gray-500"><Loader className="w-4 h-4 spin" />{label}</div>
);

export const ErrorBox = ({ children }: { children: ReactNode }) => (
  <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-[13px] text-red-700">{children}</div>
);

/** Relative time that refreshes every second. */
export function Ago({ t, icon }: { t: string; icon?: boolean }) {
  const [, tick] = useState(0);
  useEffect(() => { const iv = setInterval(() => tick(x => x + 1), 1000); return () => clearInterval(iv); }, []);
  const txt = <span title={fmtDT(t)}>{ago(t)}</span>;
  if (!icon) return txt;
  return <span className="inline-flex items-center gap-1.5 text-[13px] text-gray-500 whitespace-nowrap"><Clock className="w-3.5 h-3.5 text-gray-400" />{txt}</span>;
}

/* ---------------- data display ---------------- */

export const JsonView = ({ value, className = 'max-h-80' }: { value: unknown; className?: string }) => (
  <pre className={cx('json overflow-auto rounded-lg border border-gray-200 bg-gray-50 p-3 text-gray-800', className)} dangerouslySetInnerHTML={{ __html: highlightJSON(value) }} />
);

function KvValue({ v }: { v: unknown }) {
  if (v === null || v === undefined) return <span className="text-gray-400">—</span>;
  if (typeof v === 'number') return <span className="font-mono">{fmtNum(v)}</span>;
  if (typeof v === 'boolean') return <>{v ? 'Yes' : 'No'}</>;
  if (Array.isArray(v)) return <>{v.map(x => (typeof x === 'object' ? JSON.stringify(x) : String(x))).join(', ')}</>;
  return <>{String(v)}</>;
}

export function PayloadKV({ value }: { value: unknown }) {
  const pairs = flattenKV(value);
  if (!pairs.length) return <div className="text-[13px] text-gray-400">No fields in this event</div>;
  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
      {pairs.map(([k, v]) => (
        <div key={k} className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 min-w-0">
          <dt className="text-[11px] text-gray-500 truncate" title={k}>{kvLabel(k)}</dt>
          <dd className="mt-0.5 text-sm font-medium text-gray-900 truncate" title={String(typeof v === 'object' ? JSON.stringify(v) : v)}><KvValue v={v} /></dd>
        </div>
      ))}
    </div>
  );
}

/** Human-readable condition: `trade.notional` at least 10,000,000 and … */
export function CondHuman({ c, top = true, empty = 'Every event routed to the team' }: { c: Condition | null | undefined; top?: boolean; empty?: string }): JSX.Element {
  if (!c) return <span className="text-gray-400">{empty}</span>;
  if (!isLeaf(c)) {
    const op = isAnd(c) ? 'and' : 'or';
    const parts = children(c);
    if (!parts.length) return <span className="text-gray-400">—</span>;
    const inner = parts.map((ch, i) => (
      <span key={i}>{i > 0 && <span className="text-gray-400 font-medium"> {op} </span>}<CondHuman c={ch} top={false} /></span>
    ));
    return top || parts.length === 1 ? <>{inner}</> : <><span className="text-gray-400">(</span>{inner}<span className="text-gray-400">)</span></>;
  }
  const v = Array.isArray(c.value) ? c.value.join(', ') : c.value;
  return (
    <>
      <code className="code">{c.path}</code> <span className="text-gray-500">{OPLABEL[c.op] || c.op}</span>
      {c.op === 'exists'
        ? c.value === false && <span className="text-gray-500"> (no)</span>
        : <> <span className="font-medium text-gray-900">{typeof v === 'number' ? fmtNum(v) : String(v ?? '')}</span></>}
    </>
  );
}

const TEAM_COLORS = ['#6366F1', '#10B981', '#EC4899', '#F59E0B', '#0EA5E9', '#8B5CF6', '#14B8A6', '#F97316'];
export const teamColor = (id: number | null | undefined) => (id == null ? '#9CA3AF' : TEAM_COLORS[(id - 1) % TEAM_COLORS.length]);

export function Scope({ teamId, name }: { teamId: number | null | undefined; name?: string }) {
  if (teamId == null) return <span className="inline-flex items-center gap-1.5 text-[13px] text-gray-700"><Globe className="w-3.5 h-3.5 text-gray-400" />Global</span>;
  return (
    <span className="inline-flex items-center gap-1.5 text-[13px] text-gray-700 whitespace-nowrap">
      <span className="h-2 w-2 rounded-full" style={{ background: teamColor(teamId) }} />{name ?? `Team #${teamId}`}
    </span>
  );
}

export function TeamName({ name }: { name: string | null }) {
  return (
    <span className={cx('inline-flex items-center gap-1.5 text-[13px] whitespace-nowrap', name ? 'text-gray-600' : 'text-gray-400')}>
      <Users className={cx('w-3.5 h-3.5', name && 'text-gray-400')} />{name ?? 'No team'}
    </span>
  );
}

const fileIcon = (n: string): [LucideIcon, string] =>
  /\.pdf$/i.test(n) ? [FileText, 'text-red-600 bg-red-50']
    : /\.(md|markdown|ya?ml|json)$/i.test(n) ? [FileCode, 'text-blue-600 bg-blue-50']
      : /\.docx?$/i.test(n) ? [FileType, 'text-indigo-600 bg-indigo-50']
        : [File, 'text-gray-600 bg-gray-100'];
export function FileGlyph({ name }: { name: string }) {
  const [Ic, c] = fileIcon(name);
  return <span className={`h-9 w-9 shrink-0 rounded-lg grid place-items-center ${c}`}><Ic className="w-4 h-4" /></span>;
}

/* ---------------- modal ---------------- */

export function Modal({ open, onClose, size = 'max-w-2xl', children }: { open: boolean; onClose: () => void; size?: string; children: ReactNode }) {
  useEffect(() => {
    if (!open) return;
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="overlay open fixed inset-0 z-[60] bg-gray-900/40 flex items-start justify-center p-3 sm:p-6 overflow-y-auto" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={cx('w-full rounded-xl bg-white shadow-2xl my-auto fade-in', size)}>{children}</div>
    </div>
  );
}

export function ModalHead({ title, sub, onClose }: { title: ReactNode; sub?: ReactNode; onClose: () => void }) {
  return (
    <div className="flex items-start gap-3 px-6 py-4 border-b border-gray-200">
      <div className="min-w-0">
        <h3 className="text-base font-semibold text-gray-900">{title}</h3>
        {sub && <p className="text-[13px] text-gray-500 mt-0.5">{sub}</p>}
      </div>
      <button className="ml-auto btn btn-ghost btn-icon -mr-2" onClick={onClose} aria-label="Close"><X className="w-5 h-5" /></button>
    </div>
  );
}

export const ModalFoot = ({ children, error }: { children: ReactNode; error?: ReactNode }) => (
  <div className="flex flex-wrap items-center justify-end gap-2 px-6 py-3 border-t border-gray-200 bg-gray-50 rounded-b-xl">
    {error !== undefined && <div className="text-[13px] text-red-600 min-w-0 flex-1 truncate" title={typeof error === 'string' ? error : undefined}>{error}</div>}
    {children}
  </div>
);

export function ConfirmModal({ open, title, body, label = 'Confirm', busy, onConfirm, onClose }: {
  open: boolean; title: string; body: ReactNode; label?: string; busy?: boolean; onConfirm: () => void; onClose: () => void;
}) {
  return (
    <Modal open={open} onClose={onClose} size="max-w-md">
      <ModalHead title={title} onClose={onClose} />
      <div className="px-6 py-4 text-sm text-gray-600">{body}</div>
      <ModalFoot>
        <button className="btn btn-white" onClick={onClose}>Cancel</button>
        <button className="btn btn-danger" disabled={busy} onClick={onConfirm}>{busy && <Loader className="w-4 h-4 spin" />}{label}</button>
      </ModalFoot>
    </Modal>
  );
}
