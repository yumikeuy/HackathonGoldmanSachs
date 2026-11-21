/**
 * What the signed-in user may do in the UI — mirrors the server's rules (docs/security.md), which enforce them anyway.
 * Without enforced login nobody is signed in and everything is allowed, as before.
 *   VIEWER   — own team's alerts, rules and runbooks, read-only
 *   OPERATOR — plus ack / resolve
 *   ADMIN    — everything: all teams, unrouted, configuration, sources, simulator, failed events
 */
import { useSyncExternalStore } from 'react';
import { session } from './session';

export interface Permissions {
  /** Sees every team and the operational pages; may change configuration. */
  admin: boolean;
  /** May acknowledge and resolve alerts. */
  operate: boolean;
  /** Limited to one team: the user's own (`teamId`). */
  restricted: boolean;
  teamId: number | null;
}

export function permissionsOf(user = session.user): Permissions {
  if (!user) return { admin: true, operate: true, restricted: false, teamId: null };
  const admin = user.role === 'ADMIN';
  return { admin, operate: admin || user.role === 'OPERATOR', restricted: !admin, teamId: user.teamId ?? null };
}

export function usePermissions(): Permissions {
  const user = useSyncExternalStore(l => session.subscribe(l), () => session.user);
  return permissionsOf(user);
}

/** Pages a restricted user may open; the rest are for administrators. */
export const RESTRICTED_PATHS = ['/alerts', '/rules', '/runbooks'];
