/** Recursive AND/OR condition builder (rules, auto-resolve, team routing, runbooks), ported from the prototype. */
import { ListTree, Plus, Trash2, X } from 'lucide-react';
import { Autocomplete } from './Autocomplete';
import { useFieldSuggest } from '../hooks/useFieldSuggest';
import { OPLABEL, OPS, leafError, newGroup, newLeaf, placeholderFor, type EdGroup, type EdLeaf, type EdNode } from '../utils/condition';
import { cx } from './ui';
import type { Operator } from '../types';

interface Props {
  value: EdGroup;
  onChange: (g: EdGroup) => void;
  /** Show "required" errors (after a save attempt). */
  show?: boolean;
  strict?: boolean;
}

/** Returns a copy of the tree with `fn` applied to the node with `id`. */
function mapNode(g: EdGroup, id: number, fn: (n: EdNode) => EdNode | null): EdGroup {
  const kids: EdNode[] = [];
  for (const ch of g.c) {
    if (ch.id === id) {
      const r = fn(ch);
      if (r) kids.push(r);
    } else kids.push(ch.k === 'g' ? mapNode(ch, id, fn) : ch);
  }
  return { ...g, c: kids };
}

export function ConditionBuilder({ value, onChange, show = false, strict = false }: Props) {
  const fs = useFieldSuggest(strict);

  const update = (id: number, fn: (n: EdNode) => EdNode | null) => {
    if (value.id === id) {
      const r = fn(value);
      onChange((r as EdGroup) ?? { ...value, c: [] });
    } else onChange(mapNode(value, id, fn));
  };

  const renderGroup = (g: EdGroup, depth: number, isRoot: boolean): JSX.Element => (
    <div key={g.id} className={cx('rounded-xl border border-gray-200 p-3', depth % 2 ? 'bg-white' : 'bg-gray-50')}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-[13px] text-gray-500">
          Match <b className="font-medium text-gray-900">{g.op === 'and' ? 'all' : 'any'}</b> of these
          {g.c.length > 1 ? ' — click the AND/OR badge between conditions to switch' : ''}
        </span>
        <div className="ml-auto flex items-center gap-1">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => update(g.id, n => ({ ...(n as EdGroup), c: [...(n as EdGroup).c, newLeaf()] }))}>
            <Plus className="w-3.5 h-3.5" />Condition
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => update(g.id, n => ({ ...(n as EdGroup), c: [...(n as EdGroup).c, newGroup(g.op === 'and' ? 'or' : 'and')] }))}>
            <ListTree className="w-3.5 h-3.5" />Group
          </button>
          {!isRoot && (
            <button type="button" className="btn btn-ghost btn-sm hover:!text-red-600" title="Remove group" onClick={() => update(g.id, () => null)}>
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>
      <div className="mt-3 space-y-2">
        {g.c.length === 0 && <div className="text-[13px] text-gray-400 py-1">{isRoot ? 'No conditions yet.' : 'Empty group. Add a condition or remove it.'}</div>}
        {g.c.map((ch, i) => (
          <div key={ch.id}>
            {i > 0 && (
              <div className="flex items-center gap-2 mb-2">
                <button
                  type="button"
                  className={cx('rounded-md px-1.5 py-px text-[10.5px] font-semibold uppercase tracking-wide transition hover:ring-2', g.op === 'and' ? 'bg-blue-50 text-blue-700 hover:ring-blue-200' : 'bg-violet-50 text-violet-700 hover:ring-violet-200')}
                  title={`Click to switch to ${g.op === 'and' ? 'OR' : 'AND'}`}
                  onClick={() => update(g.id, n => ({ ...(n as EdGroup), op: g.op === 'and' ? 'or' : 'and' }))}
                >{g.op}</button>
                <span className="h-px flex-1 bg-gray-200" />
              </div>
            )}
            {ch.k === 'g' ? renderGroup(ch, depth + 1, false) : renderLeaf(ch)}
          </div>
        ))}
      </div>
    </div>
  );

  const renderLeaf = (n: EdLeaf) => {
    const e = leafError(n, show);
    const set = (patch: Partial<EdLeaf>) => update(n.id, x => ({ ...(x as EdLeaf), ...patch }));
    const isList = n.op === 'in' || n.op === 'not_in';
    return (
      <div>
        <div className="flex items-start gap-2">
          <div className="grid flex-1 grid-cols-1 sm:grid-cols-[minmax(0,1.2fr)_170px_minmax(0,1fr)] gap-2">
            <div className="relative">
              <Autocomplete
                className={cx('inp inp-sm w-full font-mono pr-14', e.path && 'inp-err')}
                value={n.path}
                onChange={v => set({ path: v })}
                suggest={fs.suggestPaths}
                header="Fields seen in recent events"
                placeholder="field, e.g. trade.notional"
              />
              <span className="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-[11px] text-gray-400">{fs.typeOf(n.path)}</span>
            </div>
            <select
              className="inp inp-sm"
              value={n.op}
              onChange={ev => {
                const op = ev.target.value as Operator;
                set({ op, value: op === 'exists' ? 'true' : n.value === 'true' || n.value === 'false' ? '' : n.value });
              }}
            >
              {OPS.map(o => <option key={o} value={o}>{o}  ·  {OPLABEL[o]}</option>)}
            </select>
            {n.op === 'exists' ? (
              <select className="inp inp-sm w-full" value={n.value === 'false' ? 'false' : 'true'} onChange={ev => set({ value: ev.target.value })}>
                <option value="true">yes</option>
                <option value="false">no</option>
              </select>
            ) : (
              <Autocomplete
                className={cx('inp inp-sm w-full font-mono', e.value && 'inp-err')}
                value={n.value}
                onChange={v => set({ value: v })}
                suggest={q => (['regex', 'glob', 'contains'].includes(n.op) ? [] : fs.suggestValues(n.path, q))}
                header="Values seen recently"
                placeholder={placeholderFor(n.op)}
                onPick={it => {
                  if (isList) {
                    const parts = n.value.split(',').map(s => s.trim());
                    parts[parts.length - 1] = it.value;
                    set({ value: parts.filter(Boolean).join(', ') + ', ' });
                  } else set({ value: it.value });
                }}
              />
            )}
          </div>
          <button type="button" className="btn btn-ghost h-8 w-8 px-0 shrink-0 hover:!text-red-600" title="Remove condition" onClick={() => update(n.id, () => null)}>
            <X className="w-4 h-4" />
          </button>
        </div>
        {e.msg && <div className="text-xs text-red-600 mt-1">{e.msg}</div>}
      </div>
    );
  };

  return renderGroup(value, 0, true);
}
