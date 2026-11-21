/** Formatting helpers shared by all pages. Times are shown in the browser's time zone. */

export const fmtNum = (n: number | null | undefined) => Number(n ?? 0).toLocaleString('en-US');

const dtT = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
const dtF = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });

const toMs = (t: string | number | Date) => (typeof t === 'number' ? t : new Date(t).getTime());
export const fmtT = (t: string | number) => dtT.format(toMs(t));
export const fmtDT = (t: string | number | null | undefined) => (t == null ? '—' : dtF.format(toMs(t)));

export function ago(t: string | number, now = Date.now()): string {
  const s = Math.max(0, Math.round((now - toMs(t)) / 1000));
  if (s < 5) return 'just now';
  if (s < 60) return s + 's ago';
  const m = Math.floor(s / 60);
  if (m < 60) return m + 'm ago';
  const h = Math.floor(m / 60);
  if (h < 24) return h + 'h ago';
  return Math.floor(h / 24) + 'd ago';
}

export const fmtBytes = (b: number) =>
  b < 1024 ? b + ' B' : b < 1048576 ? (b / 1024).toFixed(1) + ' KB' : (b / 1048576).toFixed(2) + ' MB';

export const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function daysLabel(days: number[]): string {
  const s = [...days].sort((a, b) => a - b);
  if (s.join() === '1,2,3,4,5') return 'Mon–Fri';
  if (s.length === 7) return 'Every day';
  if (!s.length) return 'No days';
  return s.map(d => DAYS[d - 1]).join(', ');
}

function partsIn(tz: string) {
  try {
    return Object.fromEntries(
      new Intl.DateTimeFormat('en-GB', { timeZone: tz, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
        .formatToParts(Date.now()).map(p => [p.type, p.value]),
    ) as Record<string, string>;
  } catch {
    return null;
  }
}

export function localTime(tz: string): string {
  const p = partsIn(tz);
  return p ? `${p.hour}:${p.minute}` : '—';
}

/** Whether `hours` ("08-20", end exclusive) contains the current hour in `tz`; `null` — any time. */
export function inWindow(hours: string | null | undefined, tz: string): boolean {
  if (!hours) return true;
  const p = partsIn(tz);
  if (!p) return true;
  const [a, b] = hours.split('-').map(Number);
  const h = Number(p.hour);
  return a <= b ? h >= a && h < b : h >= a || h < b;
}

export function inWorkingHours(wh: { from: string; to: string; days: number[] } | null | undefined, tz: string): boolean {
  if (!wh) return true;
  const p = partsIn(tz);
  if (!p) return true;
  const day = DAYS.indexOf(p.weekday) + 1;
  const hm = `${p.hour}:${p.minute}`;
  return wh.days.includes(day) && hm >= wh.from && hm < wh.to;
}

export function highlightJSON(obj: unknown): string {
  const json = (JSON.stringify(obj, null, 2) ?? 'undefined').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return json.replace(/("(\\u[a-fA-F0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+-]?\d+)?)/g, m => {
    let cls = 'j-num';
    if (/^"/.test(m)) cls = /:$/.test(m) ? 'j-key' : 'j-str';
    else if (/true|false/.test(m)) cls = 'j-bool';
    else if (/null/.test(m)) cls = 'j-null';
    return `<span class="${cls}">${m}</span>`;
  });
}

/** Flattens nested objects into dotted key/value pairs for the payload grid. */
export function flattenKV(obj: unknown, pre = ''): Array<[string, unknown]> {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return pre ? [[pre, obj]] : [['value', obj]];
  const out: Array<[string, unknown]> = [];
  for (const [k, v] of Object.entries(obj as Record<string, unknown>)) {
    const label = pre ? pre + '.' + k : k;
    if (v && typeof v === 'object' && !Array.isArray(v)) out.push(...flattenKV(v, label));
    else out.push([label, v]);
  }
  return out;
}

export const kvLabel = (k: string) => k.split('.').pop()!.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());

export async function copyText(t: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(t);
    return true;
  } catch {
    return false;
  }
}

/** "Unrouted event from <source>" — the backend's title for UNROUTED alerts. */
export function unroutedSource(title: string): string | null {
  const m = /^Unrouted event from (.+)$/.exec(title);
  return m ? m[1] : null;
}

export const plural = (n: number, one: string, many = one + 's') => `${fmtNum(n)} ${n === 1 ? one : many}`;
