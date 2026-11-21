/** Rules of all teams in priority order: toggle, duplicate, delete, open the editor. */
import { useMemo, useState } from 'react';
import { Copy, Plus, Trash2, Workflow } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ChanBadge, CondHuman, ConfirmModal, Empty, PageIntro, PageWrap, Scope, Search, Sev, Spinner, Toggle, cx } from '../../components/ui';
import { condText } from '../../utils/condition';
import { groupingLabel, ruleToInput } from '../../utils/rules';
import type { Rule } from '../../types';
import { usePermissions } from '../../auth/permissions';

export function RulesPage() {
  const { admin } = usePermissions();
  const { rules, teams, configLoaded, teamName, reloadRules, openRuleEditor } = useStore();
  const toast = useToast();
  const [q, setQ] = useState('');
  const [teamFilter, setTeamFilter] = useState<number | 'all'>('all');
  const [deleting, setDeleting] = useState<Rule | null>(null);
  const [busy, setBusy] = useState(false);

  const list = useMemo(() => {
    const s = q.toLowerCase();
    return rules
      .filter(r => (teamFilter === 'all' || r.teamId === teamFilter) && (!s || `${r.name} ${condText(r.condition)}`.toLowerCase().includes(s)))
      .sort((a, b) => (teamName(a.teamId) ?? '').localeCompare(teamName(b.teamId) ?? '') || (a.priority ?? 100) - (b.priority ?? 100));
  }, [rules, q, teamFilter, teamName]);

  const toggle = async (r: Rule) => {
    try {
      await api.updateRule(r.id, { ...ruleToInput(r), active: !r.active });
      await reloadRules();
      toast({ title: r.active ? 'Rule turned off' : 'Rule turned on', body: r.name, tone: 'ok' });
    } catch (e) {
      toast({ title: 'Could not change the rule', body: errorText(e), tone: 'err' });
    }
  };

  const del = async () => {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.deleteRule(deleting.id);
      await reloadRules();
      toast({ title: 'Rule deleted', body: deleting.name, tone: 'ok' });
      setDeleting(null);
    } catch (e) {
      toast({ title: 'Could not delete the rule', body: errorText(e), tone: 'err' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <PageWrap wide>
      <PageIntro
        right={
          <>
            <select className="inp" value={teamFilter} onChange={e => setTeamFilter(e.target.value === 'all' ? 'all' : +e.target.value)}>
              <option value="all">All teams</option>
              {teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
            <Search value={q} onChange={setQ} placeholder="Search rules…" className="w-56" />
            {admin && <button className="btn btn-dark" onClick={() => openRuleEditor({ prefill: teamFilter !== 'all' ? { teamId: teamFilter } : {} })}><Plus className="w-4 h-4" />New rule</button>}
          </>
        }
      >
        Rules decide when an event becomes an alert, how similar events are grouped, and who gets notified. Each rule belongs to one team and runs in priority order.
      </PageIntro>
      <div className="card overflow-x-auto">
        <table className="w-full">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              <th className="th pl-5">Rule</th><th className="th">Team</th><th className="th">Severity</th><th className="th">Notify via</th>
              <th className="th">Grouping</th><th className="th">Priority</th><th className="th">Active</th><th className="th pr-5" />
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {!configLoaded ? <tr><td colSpan={8}><Spinner /></td></tr> : list.length ? list.map(r => (
              <tr key={r.id} className={cx('hover:bg-gray-50 cursor-pointer', !r.active && 'opacity-50')} onClick={() => openRuleEditor({ rule: r })}>
                <td className="td pl-5 min-w-[300px]">
                  <div className="font-medium text-gray-900">{r.name}</div>
                  <div className="mt-1 text-[13px] text-gray-600 leading-6"><CondHuman c={r.condition} /></div>
                  {r.resolveCondition && <div className="text-xs text-gray-400 mt-0.5">Auto-resolves when <CondHuman c={r.resolveCondition} /></div>}
                </td>
                <td className="td whitespace-nowrap"><Scope teamId={r.teamId} name={teamName(r.teamId) ?? undefined} /></td>
                <td className="td"><Sev s={r.assignedSeverity ?? 'MEDIUM'} /></td>
                <td className="td"><div className="flex flex-wrap gap-1 max-w-[220px]">{(r.channels ?? []).map(c => <ChanBadge key={c} ch={c} />)}</div></td>
                <td className="td text-[13px] text-gray-600 whitespace-nowrap">
                  {groupingLabel(r)}
                  {(r.groupByPaths ?? []).length > 0 && <div className="text-xs text-gray-400">by {r.groupByPaths!.join(', ')}</div>}
                </td>
                <td className="td text-[13px] text-gray-600">{r.priority}</td>
                <td className="td"><Toggle on={r.active ?? true} onChange={() => toggle(r)} label="Rule active" disabled={!admin} /></td>
                <td className="td pr-5">
                  {admin && <div className="flex justify-end gap-0.5" onClick={e => e.stopPropagation()}>
                    <button className="btn btn-ghost btn-sm w-7 px-0" title="Duplicate" onClick={() => openRuleEditor({ prefill: { ...ruleToInput(r), name: `${r.name} (copy)`, priority: (r.priority ?? 100) + 1 } })}>
                      <Copy className="w-3.5 h-3.5" />
                    </button>
                    <button className="btn btn-ghost btn-sm w-7 px-0 hover:!text-red-600" title="Delete" onClick={() => setDeleting(r)}><Trash2 className="w-3.5 h-3.5" /></button>
                  </div>}
                </td>
              </tr>
            )) : (
              <tr><td colSpan={8}><Empty icon={Workflow} title="No rules here yet" body="Create a rule, or run infra/seed/seed.sh for the demo set." /></td></tr>
            )}
          </tbody>
        </table>
      </div>
      <ConfirmModal
        open={!!deleting}
        title="Delete this rule?"
        body={<><b className="text-gray-900 font-medium">{deleting?.name}</b> stops matching new events. A rule that already opened alerts cannot be deleted — turn it off instead.</>}
        label="Delete rule"
        busy={busy}
        onConfirm={del}
        onClose={() => setDeleting(null)}
      />
    </PageWrap>
  );
}
