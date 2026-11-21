/**
 * Friendly aliases over the types generated from `openapi.yaml` (`npm run gen:types` regenerates `api.ts`).
 * Never edit `api.ts` by hand — the contract is owned by the backend roles.
 */
import type { components } from './api';

type S = components['schemas'];

export type Severity = S['Severity'];
export type AlertSeverity = S['AlertSeverity'];
export type AlertStatus = S['AlertStatus'];
export type Channel = S['Channel'];
export type SourceType = S['SourceType'];
export type GroupMode = S['GroupMode'];
export type Operator = S['Operator'];

/** Condition tree (AGENTS.md §5). Written by hand: the generated `oneOf` is awkward to narrow. */
export type LeafCondition = { path: string; op: Operator; value?: unknown };
export type AndCondition = { and: Condition[] };
export type OrCondition = { or: Condition[] };
export type Condition = AndCondition | OrCondition | LeafCondition;

/** `config` is type-specific (`{topic}` for kafka, `{path}` for file, `{}` for http). */
export type SourceInput = Omit<S['SourceInput'], 'config'> & { config?: Record<string, unknown> };
export type Source = Omit<S['Source'], 'config'> & { config?: Record<string, unknown> };
export type SourceApiKey = S['SourceApiKey'];
export type Team = Omit<S['Team'], 'condition'> & { condition?: Condition | null };
export type TeamInput = Omit<S['TeamInput'], 'condition'> & { condition?: Condition | null };
export type ChannelPreference = S['ChannelPreference'];
export type WorkingHours = S['WorkingHours'];
export type User = S['User'];
export type UserInput = S['UserInput'];

export type Rule = Omit<S['Rule'], 'condition' | 'resolveCondition'> & {
  condition?: Condition | null;
  resolveCondition?: Condition | null;
};
export type RuleInput = Omit<S['RuleInput'], 'condition' | 'resolveCondition'> & {
  condition?: Condition | null;
  resolveCondition?: Condition | null;
};
export type DryRunRequest = Omit<S['DryRunRequest'], 'condition' | 'strict' | 'sampleSize'> & { condition: Condition; strict?: boolean; sampleSize?: number };
export type DryRunResult = S['DryRunResult'];

export type AlertSummary = S['AlertSummary'];
export type AlertDetail = S['AlertDetail'];
export type AlertPage = S['AlertPage'];
export type TeamSuggestion = S['TeamSuggestion'];
export type AuditEntry = S['AuditEntry'];

export type RunbookLink = S['RunbookLink'];
export type Runbook = Omit<S['Runbook'], 'condition'> & { condition?: Condition | null };

export type Delivery = S['Delivery'];
export type DeliveryPage = S['DeliveryPage'];
export type ChannelState = S['ChannelState'];

export type IngestLag = S['IngestLag'];
export type FieldInfo = S['FieldInfo'];
export type FailedEvent = S['FailedEvent'];
export type FailedEventPage = S['FailedEventPage'];

export type GenerateRequest = S['GenerateRequest'];
export type GenerateAccepted = S['GenerateAccepted'];
export type Problem = S['Problem'];

export type Json = unknown;
