/**
 * Add / edit user: contact, team, time zone, working hours, channel preferences in priority order, and UI login —
 * role and password (write-only: the server stores a BCrypt hash and only reports `canLogIn`).
 */
import { useState } from 'react';
import { ArrowDown, ArrowUp, KeyRound, Loader } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { CHAN, Modal, ModalFoot, ModalHead, Toggle } from '../../components/ui';
import { DAYS } from '../../utils/format';
import type { Channel, ChannelPreference, User, UserInput } from '../../types';

type Role = NonNullable<UserInput['role']>;

const ROLES: Array<[Role, string, string]> = [
  ['VIEWER', 'Viewer', 'sees alerts and configuration'],
  ['OPERATOR', 'Operator', 'also acknowledges and resolves alerts'],
  ['ADMIN', 'Admin', 'also changes teams, users, rules, runbooks and sources'],
];

type Mode = 'always' | 'working' | 'custom';
interface PrefDraft { channel: Channel; on: boolean; mode: Mode; hours: string }

const TZ_OPTIONS = ['Europe/Warsaw', 'Europe/London', 'Europe/Berlin', 'America/New_York', 'America/Chicago', 'Asia/Singapore', 'Asia/Tokyo', 'UTC'];
const DEFAULT_ORDER: Channel[] = ['EMAIL', 'SMS', 'CALL'];
const HOURS_RE = /^\d{2}-\d{2}$/;

const hoursFromWorking = (wh: { from: string; to: string }) => `${wh.from.slice(0, 2)}-${wh.to.slice(0, 2)}`;

function prefsToDraft(prefs: ChannelPreference[], wh: { from: string; to: string }): PrefDraft[] {
  const listed = prefs.filter(p => DEFAULT_ORDER.includes(p.channel)).map(p => ({
    channel: p.channel, on: true,
    mode: (!p.hours ? 'always' : p.hours === hoursFromWorking(wh) ? 'working' : 'custom') as Mode,
    hours: p.hours ?? '08-20',
  }));
  const rest = DEFAULT_ORDER.filter(c => !listed.some(p => p.channel === c)).map(c => ({ channel: c, on: false, mode: 'always' as Mode, hours: '08-20' }));
  return [...listed, ...rest];
}

export function UserModal({ user, onClose }: { user: User | null | 'new'; onClose: () => void }) {
  if (!user) return null;
  return <UserModalInner key={user === 'new' ? 'new' : user.id} user={user === 'new' ? null : user} onClose={onClose} />;
}

function UserModalInner({ user, onClose }: { user: User | null; onClose: () => void }) {
  const { teams, reloadUsers } = useStore();
  const toast = useToast();
  const wh0 = user?.workingHours ?? { from: '08:00', to: '20:00', days: [1, 2, 3, 4, 5] };
  const [name, setName] = useState(user?.name ?? '');
  const [email, setEmail] = useState(user?.email ?? '');
  const [phone, setPhone] = useState(user?.phone ?? '');
  const [teamId, setTeamId] = useState<number | null>(user?.teamId ?? teams[0]?.id ?? null);
  const [tz, setTz] = useState(user?.timezone ?? 'Europe/Warsaw');
  const [active, setActive] = useState(user?.active ?? true);
  const [hasWh, setHasWh] = useState(user ? user.workingHours != null : true);
  const [wh, setWh] = useState({ from: wh0.from, to: wh0.to, days: [...wh0.days] });
  const [prefs, setPrefs] = useState<PrefDraft[]>(() => prefsToDraft(user?.channelPreferences ?? [{ channel: 'EMAIL' }], wh0));
  const [role, setRole] = useState<Role>(user?.role ?? 'VIEWER');
  const [password, setPassword] = useState('');
  const [disableLogin, setDisableLogin] = useState(false);
  const [err, setErr] = useState('');
  const [saving, setSaving] = useState(false);

  const setPref = (i: number, patch: Partial<PrefDraft>) => setPrefs(ps => ps.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  const move = (i: number, d: -1 | 1) => setPrefs(ps => {
    const n = ps.slice();
    const j = i + d;
    if (j < 0 || j >= n.length) return ps;
    [n[i], n[j]] = [n[j], n[i]];
    return n;
  });

  const save = async () => {
    const errs: string[] = [];
    const on = prefs.filter(p => p.on);
    if (!name.trim()) errs.push('Enter a full name');
    if (email.trim() && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) errs.push('Enter a valid email address');
    if (!teamId) errs.push('Assign a team');
    if (!on.length) errs.push('Enable at least one notification channel');
    if (on.some(p => p.channel === 'EMAIL') && !email.trim()) errs.push('Email is on — enter an email address');
    if (on.some(p => p.channel !== 'EMAIL') && !phone.trim()) errs.push('SMS / Call is on — enter a phone number');
    if (on.some(p => p.mode === 'working') && !hasWh) errs.push('“Working hours only” needs working hours');
    if (on.some(p => p.mode === 'custom' && !HOURS_RE.test(p.hours))) errs.push('Custom hours look like 08-20');
    if (hasWh && !wh.days.length) errs.push('Pick at least one working day');
    if (password && password.length < 8) errs.push('The password needs at least 8 characters');
    if (password && password.length > 72) errs.push('The password can have at most 72 characters');
    if (password && !email.trim()) errs.push('Login needs an email address — it is the user name');
    if (errs.length) { setErr(errs[0]); return; }

    const body: UserInput = {
      teamId: teamId!, name: name.trim(), email: email.trim() || null, phone: phone.trim() || null, timezone: tz, active,
      workingHours: hasWh ? { from: wh.from, to: wh.to, days: [...wh.days].sort((a, b) => a - b) } : null,
      channelPreferences: on.map(p => ({ channel: p.channel, hours: p.mode === 'always' ? null : p.mode === 'working' ? hoursFromWorking(wh) : p.hours })),
      role,
      // omitted: keep the current password; "": disable login
      ...(disableLogin ? { password: '' } : password ? { password } : {}),
    };
    setSaving(true);
    setErr('');
    try {
      if (user) await api.updateUser(user.id, body); else await api.createUser(body);
      await reloadUsers();
      toast({ title: user ? 'User saved' : 'User added', body: body.name, tone: 'ok' });
      onClose();
    } catch (e) {
      setErr(errorText(e));
      setSaving(false);
    }
  };

  const tzOptions = TZ_OPTIONS.includes(tz) ? TZ_OPTIONS : [tz, ...TZ_OPTIONS];

  return (
    <Modal open onClose={onClose} size="max-w-2xl">
      <ModalHead title={user ? `Edit ${user.name}` : 'Add user'} sub="Users receive notifications. Each user belongs to one team." onClose={onClose} />
      <div className="px-6 py-5 space-y-5 max-h-[72vh] overflow-y-auto">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div><label className="label">Full name</label><input className="inp w-full" maxLength={100} value={name} onChange={e => setName(e.target.value)} placeholder="Jane Doe" /></div>
          <div><label className="label">Email</label><input className="inp w-full" maxLength={200} value={email} onChange={e => setEmail(e.target.value)} placeholder="jane.doe@bank.example" /></div>
          <div><label className="label">Phone</label><input className="inp w-full" maxLength={30} value={phone} onChange={e => setPhone(e.target.value)} placeholder="+48 600 000 000" /></div>
          <div>
            <label className="label">Assigned team</label>
            <select className="inp w-full" value={teamId ?? ''} onChange={e => setTeamId(+e.target.value)}>
              {teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </div>
          <div>
            <label className="label">Timezone</label>
            <select className="inp w-full" value={tz} onChange={e => setTz(e.target.value)}>{tzOptions.map(z => <option key={z} value={z}>{z}</option>)}</select>
          </div>
          <div><label className="label">Active</label><div className="h-9 flex items-center"><Toggle on={active} onChange={() => setActive(!active)} label="User active" /></div></div>
        </div>

        <div>
          <label className="label flex items-center gap-2">
            <KeyRound className="w-4 h-4 text-gray-400" />Login to this app
            <span className="ml-auto font-normal text-gray-500">
              {user?.canLogIn && !disableLogin ? 'Can log in' : disableLogin ? 'Login will be disabled' : 'Cannot log in yet'}
            </span>
          </label>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className="label font-normal text-gray-500" htmlFor="user-role">Role</label>
              <select id="user-role" className="inp w-full" value={role} onChange={e => setRole(e.target.value as Role)}>
                {ROLES.map(([value, label, hint]) => <option key={value} value={value}>{label} — {hint}</option>)}
              </select>
            </div>
            <div>
              <label className="label font-normal text-gray-500" htmlFor="user-password">
                {user?.canLogIn ? 'New password (leave empty to keep)' : 'Password (8+ characters)'}
              </label>
              <input id="user-password" className="inp w-full" type="password" autoComplete="new-password" maxLength={72}
                     value={password} disabled={disableLogin} onChange={e => setPassword(e.target.value)}
                     placeholder={user?.canLogIn ? '••••••••' : 'Set to allow login'} />
            </div>
          </div>
          {user?.canLogIn && (
            <label className="mt-2 inline-flex items-center gap-2 text-[13px] text-gray-600">
              <input type="checkbox" className="rounded border-gray-300" checked={disableLogin}
                     onChange={e => { setDisableLogin(e.target.checked); setPassword(''); }} />
              Disable login (notifications keep working)
            </label>
          )}
        </div>

        <div>
          <label className="label flex items-center gap-2">
            Working hours
            <span className="ml-auto inline-flex items-center gap-2 font-normal text-gray-500"><Toggle on={hasWh} onChange={() => setHasWh(!hasWh)} label="Has working hours" />{hasWh ? 'Set' : 'None (always reachable)'}</span>
          </label>
          {hasWh && (
            <div className="flex flex-wrap items-center gap-3">
              <input className="inp w-28" type="time" value={wh.from} onChange={e => setWh({ ...wh, from: e.target.value })} />
              <span className="text-gray-400">to</span>
              <input className="inp w-28" type="time" value={wh.to} onChange={e => setWh({ ...wh, to: e.target.value })} />
              <div className="flex flex-wrap gap-2.5 ml-2">
                {[1, 2, 3, 4, 5, 6, 7].map(n => (
                  <label key={n} className="inline-flex items-center gap-1.5 text-[13px] text-gray-700">
                    <input type="checkbox" className="rounded border-gray-300" checked={wh.days.includes(n)} onChange={e => setWh({ ...wh, days: e.target.checked ? [...wh.days, n] : wh.days.filter(x => x !== n) })} />
                    {DAYS[n - 1]}
                  </label>
                ))}
              </div>
            </div>
          )}
        </div>

        <div>
          <label className="label">Channel preferences &amp; priorities <span className="font-normal text-gray-400">(first enabled channel that is allowed now is used)</span></label>
          <div className="space-y-2">
            {prefs.map((p, i) => {
              const Ic = CHAN[p.channel].i;
              return (
                <div key={p.channel} className="flex flex-wrap items-center gap-3 rounded-lg border border-gray-200 px-3 py-2.5">
                  <div className="flex flex-col">
                    <button className="text-gray-400 hover:text-gray-900 disabled:opacity-30" disabled={i === 0} onClick={() => move(i, -1)} aria-label="Move up"><ArrowUp className="w-3.5 h-3.5" /></button>
                    <button className="text-gray-400 hover:text-gray-900 disabled:opacity-30" disabled={i === prefs.length - 1} onClick={() => move(i, 1)} aria-label="Move down"><ArrowDown className="w-3.5 h-3.5" /></button>
                  </div>
                  <span className="h-8 w-8 rounded-lg grid place-items-center bg-gray-100 text-gray-600"><Ic className="w-4 h-4" /></span>
                  <span className="text-sm font-medium text-gray-900 w-24">{CHAN[p.channel].l}</span>
                  <Toggle on={p.on} onChange={() => setPref(i, { on: !p.on })} label={`${CHAN[p.channel].l} enabled`} />
                  <div className="ml-auto flex items-center gap-2">
                    {p.mode === 'custom' && <input className="inp inp-sm w-20 font-mono" value={p.hours} disabled={!p.on} onChange={e => setPref(i, { hours: e.target.value })} placeholder="08-20" />}
                    <select className="inp inp-sm" value={p.mode} disabled={!p.on} onChange={e => setPref(i, { mode: e.target.value as Mode })}>
                      <option value="always">Always</option>
                      <option value="working">Working hours only</option>
                      <option value="custom">Custom hours</option>
                    </select>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </div>
      <ModalFoot error={err}>
        <button className="btn btn-white" onClick={onClose}>Cancel</button>
        <button className="btn btn-dark" onClick={save} disabled={saving}>{saving && <Loader className="w-4 h-4 spin" />}{user ? 'Save user' : 'Add user'}</button>
      </ModalFoot>
    </Modal>
  );
}
