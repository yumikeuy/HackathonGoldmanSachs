/** App shell: dark sidebar + white workspace header with the signed-in user (prototype layout; team selector removed). */
import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { Bell, BellRing, BookOpen, CircleHelp, CircleX, LogOut, Menu, Pause, Radio, Split, TriangleAlert, Users, Workflow, Zap, type LucideIcon } from 'lucide-react';
import { session } from '../auth/session';
import { RESTRICTED_PATHS, usePermissions } from '../auth/permissions';
import { useStore } from '../state/store';
import { cx } from './ui';

export interface RouteDef { path: string; label: string; icon: LucideIcon }

export const ROUTES: RouteDef[] = [
  { path: '/alerts', label: 'Alerts', icon: Bell },
  { path: '/unrouted', label: 'Unrouted', icon: Split },
  { path: '/rules', label: 'Rules', icon: Workflow },
  { path: '/runbooks', label: 'Runbooks', icon: BookOpen },
  { path: '/recipients', label: 'Teams & Users', icon: Users },
  { path: '/sources', label: 'Sources', icon: Radio },
  { path: '/simulator', label: 'Simulator', icon: Zap },
  { path: '/failed', label: 'Failed Events', icon: CircleX },
  { path: '/help', label: 'Help', icon: CircleHelp },
];

function LiveIndicator() {
  const { liveStatus, paused, togglePaused } = useStore();
  let dot: ReactNode, label: string;
  if (paused) { dot = <span className="h-2 w-2 rounded-full bg-gray-300" />; label = 'Paused'; }
  else if (liveStatus === 'live') { dot = <span className="pulse-dot h-2 w-2 rounded-full bg-emerald-500 text-emerald-500" />; label = 'Live'; }
  else if (liveStatus === 'connecting') { dot = <span className="h-2 w-2 rounded-full bg-amber-400" />; label = 'Connecting…'; }
  else { dot = <span className="h-2 w-2 rounded-full bg-red-500" />; label = 'Offline'; }
  return (
    <button onClick={togglePaused} title="Pause or resume live updates" className="inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-[13px] text-gray-500 hover:bg-gray-100">
      {dot}{label}
    </button>
  );
}

const ROLE_LABEL = { VIEWER: 'Viewer', OPERATOR: 'Operator', ADMIN: 'Admin' } as const;

/** Signed-in user and sign-out; nothing when the server does not require login and nobody signed in. */
function UserMenu() {
  const user = useSyncExternalStore(l => session.subscribe(l), () => session.user);
  if (!user) return null;
  return (
    <div className="ml-auto flex items-center gap-2 min-w-0">
      <div className="text-right min-w-0 hidden sm:block">
        <div className="text-[13px] font-medium text-gray-900 truncate">{user.name}</div>
        <div className="text-[11px] text-gray-500 truncate">{ROLE_LABEL[user.role]} · {user.email}</div>
      </div>
      <button className="btn btn-ghost btn-icon" title="Sign out" aria-label="Sign out" onClick={() => void session.logout()}>
        <LogOut className="w-4 h-4" />
      </button>
    </div>
  );
}

export function Layout({ children }: { children: ReactNode }) {
  const { alerts, failedCount, failedMore, paused, togglePaused, configError } = useStore();
  const loc = useLocation();
  const [navOpen, setNavOpen] = useState(false);
  useEffect(() => setNavOpen(false), [loc.pathname]);

  const perms = usePermissions();
  const routes = perms.restricted ? ROUTES.filter(r => RESTRICTED_PATHS.includes(r.path)) : ROUTES;
  const current = routes.find(r => loc.pathname.startsWith(r.path)) ?? routes[0];
  useEffect(() => { document.title = `${current.label} · Alerting`; }, [current.label]);

  const active = alerts.filter(a => a.status !== 'RESOLVED');
  const routedActive = active.filter(a => a.teamId != null);
  const crit = routedActive.some(a => a.status === 'OPEN' && a.severity === 'CRITICAL');
  const unrouted = active.filter(a => a.teamId == null).length;
  const badges: Record<string, ReactNode> = {
    '/alerts': routedActive.length ? <span className={cx('nav-badge', crit && '!bg-coral !text-white')}>{routedActive.length}</span> : null,
    '/unrouted': unrouted ? <span className="nav-badge">{unrouted}</span> : null,
    '/failed': failedCount ? <span className="nav-badge !bg-coral/90 !text-white">{failedCount}{failedMore ? '+' : ''}</span> : null,
  };

  return (
    <div className="flex h-screen overflow-hidden">
      <aside className={cx('fixed inset-y-0 left-0 z-40 w-60 md:translate-x-0 md:static shrink-0 bg-[#111827] flex flex-col transition-transform', !navOpen && '-translate-x-full')}>
        <div className="h-16 flex items-center gap-2.5 px-5 border-b border-white/10">
          <div className="h-7 w-7 rounded-lg bg-coral grid place-items-center"><BellRing className="w-4 h-4 text-white" /></div>
          <span className="text-[17px] font-bold tracking-tight text-white">Alerting</span>
        </div>
        <nav className="flex-1 overflow-y-auto py-3">
          {routes.map(r => {
            const Ic = r.icon;
            return (
              <NavLink key={r.path} to={r.path} className={({ isActive }) => cx('nav-item', isActive && 'active')}>
                {({ isActive }) => (
                  <>
                    <Ic className={cx('w-[18px] h-[18px] shrink-0', isActive ? 'text-white' : 'text-gray-400')} strokeWidth={1.75} />
                    <span className="truncate">{r.label}</span>
                    {badges[r.path]}
                  </>
                )}
              </NavLink>
            );
          })}
        </nav>
        <div className="px-5 py-3 border-t border-white/10 text-[11px] text-gray-500">
          <a href="/swagger-ui.html" target="_blank" rel="noreferrer" className="hover:text-gray-300">API docs</a>
          <span className="mx-1.5">·</span>
          <a href="http://localhost:8025" target="_blank" rel="noreferrer" className="hover:text-gray-300">MailHog</a>
        </div>
      </aside>
      {navOpen && <div className="fixed inset-0 z-30 bg-black/40 md:hidden" onClick={() => setNavOpen(false)} />}

      <div className="flex-1 flex flex-col min-w-0">
        <header className="h-16 shrink-0 flex items-center gap-3 bg-white border-b border-gray-200 px-4 md:px-8">
          <button className="btn btn-ghost btn-icon md:hidden" onClick={() => setNavOpen(true)} aria-label="Open navigation"><Menu className="w-5 h-5" /></button>
          <h1 className="text-xl font-semibold tracking-tight text-gray-900 truncate">{current.label}</h1>
          <LiveIndicator />
          <UserMenu />
        </header>
        {paused && (
          <div className="bg-amber-50 border-b border-amber-200 px-4 md:px-8 py-2 text-[13px] text-amber-800 flex items-center gap-2">
            <Pause className="w-4 h-4" />Live updates are paused. New alerts will appear when you resume.
            <button className="ml-auto font-medium underline" onClick={togglePaused}>Resume</button>
          </div>
        )}
        {configError && (
          <div className="bg-red-50 border-b border-red-200 px-4 md:px-8 py-2 text-[13px] text-red-800 flex items-center gap-2">
            <TriangleAlert className="w-4 h-4" />Could not load configuration: {configError}
          </div>
        )}
        <main className="flex-1 overflow-y-auto">{children}</main>
      </div>
    </div>
  );
}
