import type { Rule, RuleInput, Source, SourceInput, Team, TeamInput, User, UserInput } from '../types';

/** API entity → its input body (for PUTs that change one field). */
export const ruleToInput = (r: Rule): RuleInput => ({
  name: r.name, teamId: r.teamId, condition: r.condition ?? null, priority: r.priority, active: r.active,
  assignedSeverity: r.assignedSeverity, channels: r.channels, groupByPaths: r.groupByPaths, groupMode: r.groupMode,
  dedupWindowSec: r.dedupWindowSec, presentation: r.presentation ?? {}, resolveCondition: r.resolveCondition ?? null,
  overrideRunbookId: r.overrideRunbookId ?? null,
});

export const teamToInput = (t: Team): TeamInput => ({ name: t.name, condition: t.condition ?? null, strict: t.strict ?? false });

export const userToInput = (u: User): UserInput => ({
  teamId: u.teamId, name: u.name, email: u.email ?? null, phone: u.phone ?? null, timezone: u.timezone,
  channelPreferences: u.channelPreferences ?? [], workingHours: u.workingHours ?? null, active: u.active, role: u.role,
});

export const sourceToInput = (s: Source): SourceInput => ({ name: s.name, type: s.type, config: s.config ?? {}, active: s.active });

export const groupingLabel = (r: Pick<Rule, 'groupMode' | 'dedupWindowSec'>) => {
  const w = r.dedupWindowSec ?? 300;
  return `${r.groupMode === 'per_window' ? 'Per window' : 'While open'} · ${w >= 60 && w % 60 === 0 ? w / 60 + ' min' : w + 's'}`;
};
