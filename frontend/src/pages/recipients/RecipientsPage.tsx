/** Teams (routing conditions, strict mode) and users (channels, working hours). No admin gating: auth is decided on the backend. */
import { useState } from 'react';
import { Pencil, Plus, Trash2, Users } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ChanBadge, CondHuman, ConfirmModal, Empty, PageWrap, Scope, Seg, Spinner, Toggle, cx, teamColor } from '../../components/ui';
import { daysLabel, inWindow, inWorkingHours, localTime } from '../../utils/format';
import { teamToInput, userToInput } from '../../utils/rules';
import { TeamModal } from './TeamModal';
import { UserModal } from './UserModal';
import type { Team, User } from '../../types';

export function RecipientsPage() {
  const { teams, users, rules, alerts, configLoaded, teamName, reloadTeams, reloadUsers } = useStore();
  const toast = useToast();
  const [tab, setTab] = useState<'teams' | 'users'>('teams');
  const [editTeam, setEditTeam] = useState<Team | null | 'new'>(null);
  const [editUser, setEditUser] = useState<User | null | 'new'>(null);
  const [delTeam, setDelTeam] = useState<Team | null>(null);
  const [delUser, setDelUser] = useState<User | null>(null);
  const [busy, setBusy] = useState(false);

  const fail = (title: string) => (e: unknown) => toast({ title, body: errorText(e), tone: 'err' });

  const toggleStrict = (t: Team) =>
    api.updateTeam(t.id, { ...teamToInput(t), strict: !t.strict })
      .then(() => reloadTeams())
      .then(() => toast({ title: `Strict mode ${t.strict ? 'off' : 'on'} for ${t.name}`, tone: 'ok' }))
      .catch(fail('Could not change the team'));

  const toggleUser = (u: User) =>
    api.updateUser(u.id, { ...userToInput(u), active: !u.active })
      .then(() => reloadUsers())
      .then(() => toast({ title: `${u.name} ${u.active ? 'will not receive notifications' : 'will receive notifications again'}`, tone: 'ok' }))
      .catch(fail('Could not change the user'));

  const doDelete = async () => {
    setBusy(true);
    try {
      if (delTeam) { await api.deleteTeam(delTeam.id); await reloadTeams(); toast({ title: 'Team deleted', body: delTeam.name, tone: 'ok' }); }
      if (delUser) { await api.deleteUser(delUser.id); await reloadUsers(); toast({ title: 'User deleted', body: delUser.name, tone: 'ok' }); }
      setDelTeam(null); setDelUser(null);
    } catch (e) {
      fail('Could not delete')(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <PageWrap wide>
      <div className="flex flex-wrap items-center gap-3 mb-5">
        <Seg value={tab} onChange={setTab} options={[
          ['teams', <>Teams <span className="text-gray-400 ml-1">{teams.length}</span></>],
          ['users', <>Users <span className="text-gray-400 ml-1">{users.length}</span></>],
        ]} />
        <p className="text-[13px] text-gray-500">
          {tab === 'teams' ? 'Teams decide which events belong to them. How and when people are notified is set on each user.' : 'Users receive notifications. Each user belongs to one team.'}
        </p>
        <div className="ml-auto">
          <button className="btn btn-dark" onClick={() => (tab === 'teams' ? setEditTeam('new') : setEditUser('new'))}><Plus className="w-4 h-4" />{tab === 'teams' ? 'Add Team' : 'Add User'}</button>
        </div>
      </div>

      {!configLoaded ? <Spinner /> : tab === 'teams' ? (
        teams.length === 0 ? <div className="card"><Empty icon={Users} title="No teams yet" body="Add a team, or run infra/seed/seed.sh for the demo set." /></div> : (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {teams.map(t => {
              const us = users.filter(u => u.teamId === t.id).length;
              const rs = rules.filter(r => r.teamId === t.id).length;
              const act = alerts.filter(a => a.teamId === t.id && a.status !== 'RESOLVED').length;
              return (
                <div key={t.id} className="card p-5 flex flex-col">
                  <div className="flex items-start gap-3">
                    <span className="mt-1.5 h-2.5 w-2.5 rounded-full" style={{ background: teamColor(t.id) }} />
                    <div className="min-w-0">
                      <div className="text-[15px] font-semibold text-gray-900">{t.name}</div>
                      <div className="text-[13px] text-gray-500">{us} users · {rs} rules · {act} active alerts</div>
                    </div>
                    <label className="ml-auto flex items-center gap-2 text-[13px] text-gray-600" title="Strict mode: fields must match their exact path and type.">
                      Strict mode <Toggle on={t.strict ?? false} onChange={() => toggleStrict(t)} label="Strict mode" />
                    </label>
                  </div>
                  <div className="mt-4 flex-1 rounded-lg bg-gray-50 border border-gray-200 px-3 py-2.5">
                    <div className="flex items-center">
                      <span className="text-xs font-medium text-gray-500">Routing condition</span>
                      <button className="ml-auto btn btn-ghost btn-sm" onClick={() => setEditTeam(t)}><Pencil className="w-3.5 h-3.5" />Edit</button>
                      <button className="btn btn-ghost btn-sm w-7 px-0 hover:!text-red-600" title="Delete team" onClick={() => setDelTeam(t)}><Trash2 className="w-3.5 h-3.5" /></button>
                    </div>
                    <div className="mt-1 text-[13px] text-gray-700 leading-6"><CondHuman c={t.condition} empty="No events are routed to this team" /></div>
                  </div>
                  <div className="mt-3 text-xs text-gray-500">{t.strict ? 'Strict: exact field paths and types only.' : 'Flexible: a field name is also found when it sits at a different place in the event.'}</div>
                </div>
              );
            })}
          </div>
        )
      ) : (
        <div className="card overflow-x-auto">
          <table className="w-full">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="th pl-5">Name</th><th className="th">Team</th><th className="th">Phone</th><th className="th">Timezone</th>
                <th className="th">Working hours</th><th className="th">Channel preferences</th><th className="th pr-5">Active</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {users.length === 0 ? <tr><td colSpan={7}><Empty icon={Users} title="No users yet" /></td></tr> : users.map(u => {
                const inWh = inWorkingHours(u.workingHours, u.timezone ?? 'UTC');
                return (
                  <tr key={u.id} className={cx('hover:bg-gray-50', !u.active && 'opacity-50')}>
                    <td className="td pl-5"><div className="text-sm font-medium text-gray-900">{u.name}</div><div className="text-[13px] text-gray-500">{u.email ?? '—'}</div></td>
                    <td className="td"><Scope teamId={u.teamId} name={teamName(u.teamId) ?? undefined} /></td>
                    <td className="td text-[13px] text-gray-600 whitespace-nowrap">{u.phone ?? '—'}</td>
                    <td className="td text-[13px] text-gray-600 whitespace-nowrap">{u.timezone}<div className="text-xs text-gray-400">{localTime(u.timezone ?? 'UTC')} local time</div></td>
                    <td className="td text-[13px] text-gray-700 whitespace-nowrap">
                      {u.workingHours ? <>
                        {u.workingHours.from}–{u.workingHours.to} {daysLabel(u.workingHours.days)}
                        <div className={cx('text-xs', inWh ? 'text-emerald-600' : 'text-gray-400')}>{inWh ? 'Working now' : 'Outside working hours'}</div>
                      </> : <span className="text-gray-400">Always reachable</span>}
                    </td>
                    <td className="td">
                      <div className="flex flex-wrap gap-1">
                        {(u.channelPreferences ?? []).map(p => <ChanBadge key={p.channel} ch={p.channel} extra={p.hours ? p.hours.replace('-', '–') : 'Always'} dim={!inWindow(p.hours, u.timezone ?? 'UTC')} />)}
                      </div>
                    </td>
                    <td className="td pr-5">
                      <div className="flex items-center justify-end gap-2">
                        <button className="btn btn-ghost btn-sm w-7 px-0" title="Edit user" onClick={() => setEditUser(u)}><Pencil className="w-3.5 h-3.5" /></button>
                        <button className="btn btn-ghost btn-sm w-7 px-0 hover:!text-red-600" title="Delete user" onClick={() => setDelUser(u)}><Trash2 className="w-3.5 h-3.5" /></button>
                        <Toggle on={u.active ?? true} onChange={() => toggleUser(u)} label="User active" />
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <TeamModal team={editTeam} onClose={() => setEditTeam(null)} />
      <UserModal user={editUser} onClose={() => setEditUser(null)} />
      <ConfirmModal
        open={!!delTeam || !!delUser}
        title={delTeam ? 'Delete this team?' : 'Delete this user?'}
        body={delTeam
          ? <><b className="text-gray-900 font-medium">{delTeam.name}</b> will stop receiving events. A team that still has users, rules or alerts cannot be deleted.</>
          : <><b className="text-gray-900 font-medium">{delUser?.name}</b> will no longer be notified.</>}
        label="Delete"
        busy={busy}
        onConfirm={doDelete}
        onClose={() => { setDelTeam(null); setDelUser(null); }}
      />
    </PageWrap>
  );
}
