/**
 * Decides what to render before the app: while the server's login requirement is probed and a stored session is
 * restored, a spinner; the login screen if login is enforced and nobody is signed in; otherwise the app. Re-renders on
 * login/logout and reconnects the live feed with the new identity.
 */
import { useEffect, useState, useSyncExternalStore, type ReactNode } from 'react';
import { Spinner } from '../components/ui';
import { live } from '../ws/live';
import { LoginPage } from './LoginPage';
import { session } from './session';

const subscribe = (l: () => void) => session.subscribe(l);
const tokenSnapshot = () => session.accessToken;
/** Who is signed in; changes on login/logout, not on a token refresh. */
const identitySnapshot = () => session.user?.subject ?? null;

export function AuthGate({ children }: { children: ReactNode }) {
  const [required, setRequired] = useState<boolean | null>(null);
  const token = useSyncExternalStore(subscribe, tokenSnapshot);
  const identity = useSyncExternalStore(subscribe, identitySnapshot);

  useEffect(() => {
    (async () => {
      await session.refresh();                         // restore a session from this tab, if any
      setRequired(await session.probeLoginRequired());
    })();
  }, []);

  useEffect(() => {
    if (required !== null) live.restart();
  }, [identity, required]);

  if (required === null) return <div className="min-h-screen grid place-items-center"><Spinner label="Starting…" /></div>;
  if (required && !token) return <LoginPage />;
  return <>{children}</>;
}
