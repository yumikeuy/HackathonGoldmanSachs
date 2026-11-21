/** `GET /api/alerts/{id}/team-suggestions` — teams an UNROUTED alert probably belongs to, best first. */
import { useEffect, useState } from 'react';
import { Plus, Sparkles } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { Scope } from '../../components/ui';
import type { TeamSuggestion } from '../../types';

export function useTeamSuggestions(alertId: number | null, enabled = true) {
  const [items, setItems] = useState<TeamSuggestion[] | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    if (alertId == null || !enabled) return;
    let alive = true;
    setItems(null);
    setError('');
    api.getTeamSuggestions(alertId)
      .then(r => { if (alive) setItems(r); })
      .catch(e => { if (alive) { setError(errorText(e)); setItems([]); } });
    return () => { alive = false; };
  }, [alertId, enabled]);
  return { items, error };
}

export function TeamSuggestions({ alertId, onCreateRule }: { alertId: number; onCreateRule: (s: TeamSuggestion | null) => void }) {
  const { items, error } = useTeamSuggestions(alertId);
  return (
    <div className="rounded-xl border border-gray-200 bg-white">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-gray-100">
        <Sparkles className="w-4 h-4 text-violet-500" />
        <span className="text-[13px] font-medium text-gray-900">Suggested teams</span>
        <span className="text-xs text-gray-400">near-miss conditions + source history</span>
      </div>
      {items === null ? (
        <div className="px-3 py-3 text-[13px] text-gray-400">Scoring teams…</div>
      ) : error ? (
        <div className="px-3 py-3 text-[13px] text-red-600">{error}</div>
      ) : items.length === 0 ? (
        <div className="px-3 py-3 flex items-center gap-3 text-[13px] text-gray-500">
          No team comes close to this event.
          <button className="ml-auto btn btn-white btn-sm" onClick={() => onCreateRule(null)}><Plus className="w-3.5 h-3.5" />Create rule</button>
        </div>
      ) : (
        <div className="divide-y divide-gray-100">
          {items.map((s, i) => (
            <div key={s.teamId} className="flex items-center gap-3 px-3 py-2.5">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <Scope teamId={s.teamId} name={s.teamName} />
                  {i === 0 && <span className="rounded bg-violet-50 px-1.5 py-px text-[10.5px] font-semibold uppercase tracking-wide text-violet-700">Best match</span>}
                </div>
                <div className="mt-0.5 text-xs text-gray-500">{s.reason}</div>
                <div className="mt-1.5 h-1 w-full max-w-[220px] rounded-full bg-gray-100 overflow-hidden">
                  <div className="bar-fill h-full rounded-full bg-violet-500" style={{ width: `${Math.round(s.score * 100)}%` }} />
                </div>
              </div>
              <span className="text-[13px] font-semibold tabular-nums text-gray-700">{Math.round(s.score * 100)}%</span>
              <button className="btn btn-white btn-sm" onClick={() => onCreateRule(s)} title={`Create a rule for ${s.teamName} from this event`}><Plus className="w-3.5 h-3.5" />Rule</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
