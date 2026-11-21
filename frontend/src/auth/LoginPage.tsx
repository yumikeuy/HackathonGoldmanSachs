/** Sign-in screen, shown when the server enforces login (JWT_ENABLED=true) and there is no session. */
import { useState, type FormEvent } from 'react';
import { BellRing, Loader, LogIn } from 'lucide-react';
import { session } from './session';

export function LoginPage({ onDone }: { onDone?: () => void }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const problem = await session.login(email.trim(), password);
    setBusy(false);
    if (problem) setError(problem);
    else onDone?.();
  };

  return (
    <div className="min-h-screen grid place-items-center bg-[#111827] px-4">
      <form onSubmit={submit} className="card w-full max-w-sm p-7">
        <div className="flex items-center gap-2.5 mb-6">
          <div className="h-8 w-8 rounded-lg bg-coral grid place-items-center"><BellRing className="w-4 h-4 text-white" /></div>
          <div>
            <div className="text-[17px] font-bold tracking-tight text-gray-900">Alerting</div>
            <div className="text-[13px] text-gray-500">Sign in to continue</div>
          </div>
        </div>
        <label className="label" htmlFor="login-email">Email</label>
        <input id="login-email" className="inp w-full mb-4" type="email" autoComplete="username" autoFocus required
               value={email} onChange={e => setEmail(e.target.value)} placeholder="you@bank.example" />
        <label className="label" htmlFor="login-password">Password</label>
        <input id="login-password" className="inp w-full" type="password" autoComplete="current-password" required
               value={password} onChange={e => setPassword(e.target.value)} />
        {error && <div className="mt-3 text-[13px] text-red-600" role="alert">{error}</div>}
        <button className="btn btn-dark w-full mt-5" type="submit" disabled={busy || !email || !password}>
          {busy ? <Loader className="w-4 h-4 spin" /> : <LogIn className="w-4 h-4" />}Sign in
        </button>
        <p className="mt-4 text-xs text-gray-400 leading-5">
          Your administrator sets your password in Teams &amp; Users. Read-only users can view alerts;
          operators can also acknowledge and resolve them; administrators can change the configuration.
        </p>
      </form>
    </div>
  );
}
