/**
 * What the signed-in user may do in the UI — mirrors the server's rules (docs/security.md), which enforce them anyway.
 * Without enforced login nobody is signed in and everything is allowed, as before.
 *   VIEWER   — own team's alerts, rules and runbooks, read-only
 *   OPERATOR — plus ack / resolve, and create / edit / delete the runbooks of their own team
 *   ADMIN    — everything: all teams, unrouted, configuration (incl. global runbooks), sources, simulator, failed events
 */
import { useSyncExternalStore } from 'react';
import { session } from './session';

export interface Permissions {
  /** Sees every team and the operational pages; may change configuration. */
  admin: boolean;
  /** May acknowledge and resolve alerts. */
  operate: boolean;
  /** May create runbooks and edit / delete them — any as administrator, their own team's as operator ({@link canEditRunbook}). */
  manageRunbooks: boolean;
  /** Limited to one team: the user's own (`teamId`). */
  restricted: boolean;
  teamId: number | null;
}

export function permissionsOf(user = session.user): Permissions {
  if (!user) return { admin: true, operate: true, manageRunbooks: true, restricted: false, teamId: null };
  const admin = user.role === 'ADMIN';
  const operate = admin || user.role === 'OPERATOR';
  return { admin, operate, manageRunbooks: operate, restricted: !admin, teamId: user.teamId ?? null };
}

export function usePermissions(): Permissions {
  const user = useSyncExternalStore(l => session.subscribe(l), () => session.user);
  return permissionsOf(user);
}

/**
 * Whether this runbook may be edited or deleted: administrators any; operators only their own team's — global runbooks
 * apply to every team and stay with administrators. The server enforces the same (TeamScopeInterceptor).
 */
export function canEditRunbook(p: Permissions, runbook: { teamId?: number | null }): boolean {
  if (!p.manageRunbooks) return false;
  if (p.admin) return true;
  return runbook.teamId != null && runbook.teamId === p.teamId;
}

/** Pages a restricted user may open; the rest are for administrators. */
export const RESTRICTED_PATHS = ['/alerts', '/rules', '/runbooks', '/help'];
