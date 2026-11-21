import type { RuleEditorState } from '../state/store';
import { interestingLeaves } from './condition';

/** "Create rule from event": prefill name, condition (3 most telling fields), grouping and title from a payload. */
export function ruleFromEvent(
  payload: unknown,
  source: string | null,
  opts: { alertId?: number; teamId?: number | null; teamName?: string } = {},
): RuleEditorState {
  const chosen = interestingLeaves(payload).slice(0, 3);
  const label = source ?? 'Event';
  return {
    origin: { source, payload, alertId: opts.alertId, suggestedTeam: opts.teamName },
    prefill: {
      name: `${label}: ${chosen[0] ? String(chosen[0][1]) : 'new events'}`.slice(0, 200),
      teamId: opts.teamId ?? undefined,
      assignedSeverity: 'MEDIUM',
      condition: chosen.length ? { and: chosen.map(([path, value]) => ({ path, op: '==' as const, value })) } : null,
      groupByPaths: chosen[0] ? [chosen[0][0]] : [],
      presentation: { title: `${label}: ` + (chosen[0] ? `{{${chosen[0][0]}}}` : 'event') + (chosen[1] ? ` ({{${chosen[1][0]}}})` : '') },
    },
  };
}
