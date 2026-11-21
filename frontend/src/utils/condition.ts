/**
 * Condition-tree helpers for the UI: converting between the API shape (AGENTS.md §5) and the editor tree,
 * client-side validation, and a lightweight field lookup for previews (title templates, grouping).
 * The real evaluation always happens on the server (dry-run); nothing here decides routing.
 */
import type { Condition, LeafCondition, Operator } from '../types';

export const OPS: Operator[] = ['==', '!=', '>', '>=', '<', '<=', 'in', 'not_in', 'contains', 'regex', 'glob', 'exists'];

export const OPLABEL: Record<Operator, string> = {
  '==': 'equals', '!=': 'does not equal', '>': 'greater than', '>=': 'at least', '<': 'less than', '<=': 'at most',
  in: 'is one of', not_in: 'is not one of', contains: 'contains', regex: 'matches regex', glob: 'matches pattern', exists: 'exists',
};

export const isAnd = (c: Condition): c is { and: Condition[] } => 'and' in c;
export const isOr = (c: Condition): c is { or: Condition[] } => 'or' in c;
export const isLeaf = (c: Condition): c is LeafCondition => !isAnd(c) && !isOr(c);
export const children = (c: Condition): Condition[] => (isAnd(c) ? c.and : isOr(c) ? c.or : []);

export function condLeaves(c: Condition | null | undefined, out: LeafCondition[] = []): LeafCondition[] {
  if (!c) return out;
  if (isLeaf(c)) out.push(c);
  else children(c).forEach(ch => condLeaves(ch, out));
  return out;
}

export function condText(c: Condition | null | undefined, top = true): string {
  if (!c) return '—';
  if (!isLeaf(c)) {
    const op = isAnd(c) ? 'and' : 'or';
    const parts = children(c).map(ch => condText(ch, false));
    const j = parts.join(` ${op.toUpperCase()} `);
    return top || parts.length < 2 ? j : `(${j})`;
  }
  return `${c.path} ${c.op}${c.op === 'exists' && c.value !== false ? '' : ' ' + JSON.stringify(c.value)}`;
}

/* ---------------- editor tree ---------------- */

export type EdLeaf = { k: 'r'; id: number; path: string; op: Operator; value: string };
export type EdGroup = { k: 'g'; id: number; op: 'and' | 'or'; c: EdNode[] };
export type EdNode = EdLeaf | EdGroup;

let seq = 1;
const nid = () => seq++;

export const valueToString = (v: unknown, op: Operator) =>
  op === 'exists' ? String(v !== false) : Array.isArray(v) ? v.join(', ') : v == null ? '' : String(v);

export const newLeaf = (path = '', op: Operator = '==', value = ''): EdLeaf => ({ k: 'r', id: nid(), path, op, value });
export const newGroup = (op: 'and' | 'or', c: EdNode[] = [newLeaf()]): EdGroup => ({ k: 'g', id: nid(), op, c });

export function toEditor(c: Condition | null | undefined): EdGroup {
  if (!c) return { k: 'g', id: nid(), op: 'and', c: [] };
  if (isLeaf(c)) return { k: 'g', id: nid(), op: 'and', c: [newLeaf(c.path, c.op, valueToString(c.value, c.op))] };
  return {
    k: 'g', id: nid(), op: isAnd(c) ? 'and' : 'or',
    c: children(c).map(ch => (isLeaf(ch) ? newLeaf(ch.path, ch.op, valueToString(ch.value, ch.op)) : toEditor(ch))),
  };
}

const scalar = (s: string): unknown => {
  s = s.trim();
  if (/^-?\d+(\.\d+)?$/.test(s)) return Number(s);
  if (s === 'true') return true;
  if (s === 'false') return false;
  return s.replace(/^"(.*)"$/, '$1');
};

export function parseValue(op: Operator, s: string): unknown {
  s = String(s ?? '');
  if (op === 'exists') return s !== 'false';
  if (op === 'in' || op === 'not_in') return s.split(',').map(x => x.trim()).filter(Boolean).map(scalar);
  if (['>', '>=', '<', '<='].includes(op)) return /^\s*-?\d+(\.\d+)?\s*$/.test(s) ? Number(s) : s;
  if (op === 'regex' || op === 'glob' || op === 'contains') return s;
  return scalar(s);
}

/** Editor tree → API condition; `null` for an empty root. Empty sub-groups are dropped. */
export function fromEditor(g: EdGroup): Condition | null {
  const kids = g.c
    .map(ch => (ch.k === 'g' ? fromEditor(ch) : ({ path: ch.path.trim(), op: ch.op, value: parseValue(ch.op, ch.value) } as LeafCondition)))
    .filter((x): x is Condition => x !== null);
  if (!kids.length) return null;
  return g.op === 'and' ? { and: kids } : { or: kids };
}

export interface LeafErr { path?: boolean; value?: boolean; msg?: string }

export function leafError(n: EdLeaf, show: boolean): LeafErr {
  if (show && !n.path.trim()) return { path: true, msg: 'Choose a field' };
  if (n.op === 'exists') return {};
  if (show && !String(n.value).trim()) return { value: true, msg: 'Enter a value' };
  if (['>', '>=', '<', '<='].includes(n.op) && String(n.value).trim() && isNaN(Number(n.value)))
    return { value: true, msg: `“${n.value}” is not a number` };
  if (n.op === 'regex') {
    try { new RegExp(n.value); } catch { return { value: true, msg: 'This regular expression is not valid' }; }
  }
  if ((n.op === 'in' || n.op === 'not_in') && show && !(parseValue(n.op, n.value) as unknown[]).length)
    return { value: true, msg: 'List at least one value, separated by commas' };
  return {};
}

export function treeErrors(g: EdGroup, show = true, out: string[] = []): string[] {
  for (const ch of g.c) {
    if (ch.k === 'g') {
      if (!ch.c.length) out.push('A group has no conditions');
      treeErrors(ch, show, out);
    } else {
      const e = leafError(ch, show);
      if (e.msg) out.push(e.msg);
    }
  }
  return out;
}

export const placeholderFor = (op: Operator) =>
  ({ in: 'A, B, C', not_in: 'A, B, C', regex: '^(EUR|USD)', glob: 'prod-*', contains: 'timeout', '>': '10000000', '>=': '10000000', '<': '0', '<=': '0' } as Record<string, string>)[op] || 'value';

/* ---------------- preview helpers (not routing) ---------------- */

function deepFind(o: unknown, key: string): unknown {
  if (!o || typeof o !== 'object') return undefined;
  for (const [k, v] of Object.entries(o as Record<string, unknown>)) if (k.toLowerCase() === key) return v;
  for (const v of Object.values(o as Record<string, unknown>)) {
    if (v && typeof v === 'object') {
      const r = deepFind(v, key);
      if (r !== undefined) return r;
    }
  }
  return undefined;
}

/** Exact dotted path; in flexible mode falls back to a case-insensitive key search (like the backend). */
export function getPath(obj: unknown, path: string, strict = false): unknown {
  if (!path) return undefined;
  const segs = path.split('.');
  let cur: unknown = obj;
  for (const s of segs) {
    if (cur != null && typeof cur === 'object' && Object.prototype.hasOwnProperty.call(cur, s)) cur = (cur as Record<string, unknown>)[s];
    else { cur = undefined; break; }
  }
  if (cur !== undefined || strict) return cur;
  return deepFind(obj, segs[segs.length - 1].toLowerCase());
}

export function renderTemplate(tpl: string, payload: unknown, strict = false): string {
  return String(tpl || '').replace(/\{\{\s*([\w.\-]+)\s*\}\}/g, (_, p: string) => {
    const v = getPath(payload, p, strict);
    return v === undefined || v === null ? '∅' : typeof v === 'object' ? JSON.stringify(v) : String(v);
  });
}

/** Leaf paths of an event (for "create rule from event"), skipping IDs, timestamps and long values. */
export function interestingLeaves(payload: unknown): Array<[string, unknown]> {
  const leaves: Array<[string, unknown]> = [];
  const walk = (o: unknown, pre: string) => {
    if (!o || typeof o !== 'object' || Array.isArray(o)) return;
    for (const [k, v] of Object.entries(o as Record<string, unknown>)) {
      const p = pre ? pre + '.' + k : k;
      if (v && typeof v === 'object' && !Array.isArray(v)) walk(v, p);
      else if (v !== null && typeof v !== 'object' && !/(^|\.)(\w*_)?id$|Id$|ts$|_at$|At$|time(stamp)?$/i.test(p) && String(v).length < 40) leaves.push([p, v]);
    }
  };
  walk(payload, '');
  leaves.sort((a, b) => (typeof a[1] === 'string' ? 0 : 1) - (typeof b[1] === 'string' ? 0 : 1));
  return leaves;
}
