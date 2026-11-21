/**
 * Suggestions for the condition builder:
 *  - field paths from the field catalog `GET /api/fields` (IDs/timestamps flagged `suggested = false` go last);
 *  - values seen recently for a path, sampled with a dry-run `{path, op: exists}` over the last 1000 events.
 */
import { useCallback, useEffect } from 'react';
import { dryRun } from '../api/endpoints';
import { useStore } from '../state/store';
import { getPath } from '../utils/condition';
import type { AcItem } from '../components/Autocomplete';
import { fmtNum } from '../utils/format';

const valueCache = new Map<string, { at: number; values: string[] }>();

async function recentValues(path: string, strict: boolean): Promise<string[]> {
  const key = `${strict}|${path}`;
  const hit = valueCache.get(key);
  if (hit && Date.now() - hit.at < 15000) return hit.values;
  try {
    const r = await dryRun({ condition: { path, op: 'exists', value: true }, strict, sampleSize: 50 });
    const set = new Set<string>();
    for (const s of r.samples) {
      const v = getPath(s, path, strict);
      if (v !== undefined && v !== null && typeof v !== 'object') set.add(String(v));
    }
    const values = [...set].slice(0, 40);
    valueCache.set(key, { at: Date.now(), values });
    return values;
  } catch {
    return [];
  }
}

export function useFieldSuggest(strict = false) {
  const { fields, loadFields } = useStore();
  useEffect(() => { loadFields(); }, [loadFields]);

  const suggestPaths = useCallback((q: string): AcItem[] => {
    const s = q.trim().toLowerCase();
    return fields
      .filter(f => !s || f.path.toLowerCase().includes(s))
      .sort((a, b) => Number(b.suggested) - Number(a.suggested) || a.path.localeCompare(b.path))
      .map(f => ({
        value: f.path,
        meta: f.type ?? undefined,
        hint: f.suggested ? `~${fmtNum(f.cardinality)} distinct value${f.cardinality === 1 ? '' : 's'} (24 h)` : 'many different values',
      }));
  }, [fields]);

  const typeOf = useCallback((path: string) => fields.find(f => f.path === path)?.type ?? '', [fields]);

  const suggestValues = useCallback(async (path: string, q: string): Promise<AcItem[]> => {
    if (!path.trim()) return [];
    const f = fields.find(x => x.path === path);
    if (f && !f.suggested) return [];
    const last = q.split(',').pop()!.trim().toLowerCase();
    const values = await recentValues(path, strict);
    return values.filter(v => !last || v.toLowerCase().includes(last)).map(v => ({ value: v, meta: f?.type ?? undefined }));
  }, [fields, strict]);

  return { suggestPaths, suggestValues, typeOf };
}
