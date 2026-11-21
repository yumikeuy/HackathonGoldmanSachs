/** Add / edit team: name, strict mode, routing condition, with a live "would route here" count (dry-run). */
import { useEffect, useMemo, useState } from 'react';
import { Loader } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ConditionBuilder } from '../../components/ConditionBuilder';
import { Modal, ModalFoot, ModalHead, Toggle } from '../../components/ui';
import { fromEditor, toEditor, treeErrors } from '../../utils/condition';
import { fmtNum } from '../../utils/format';
import type { Team } from '../../types';

export function TeamModal({ team, onClose }: { team: Team | null | 'new'; onClose: () => void }) {
  if (!team) return null;
  return <TeamModalInner key={team === 'new' ? 'new' : team.id} team={team === 'new' ? null : team} onClose={onClose} />;
}

function TeamModalInner({ team, onClose }: { team: Team | null; onClose: () => void }) {
  const { reloadTeams } = useStore();
  const toast = useToast();
  const [name, setName] = useState(team?.name ?? '');
  const [strict, setStrict] = useState(team?.strict ?? false);
  const [cond, setCond] = useState(() => toEditor(team?.condition ?? null));
  const [show, setShow] = useState(false);
  const [err, setErr] = useState('');
  const [saving, setSaving] = useState(false);
  const [count, setCount] = useState<{ matched: number; checked: number } | null>(null);
  const condition = useMemo(() => fromEditor(cond), [cond]);

  useEffect(() => {
    if (!condition || treeErrors(cond, false).length) { setCount(null); return; }
    const ac = new AbortController();
    const t = setTimeout(() => {
      api.dryRun({ condition, strict, sampleSize: 0 }, ac.signal).then(r => setCount(r)).catch(() => {});
    }, 500);
    return () => { clearTimeout(t); ac.abort(); };
  }, [JSON.stringify(condition), strict]); // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    setShow(true);
    if (!name.trim()) { setErr('Give the team a name'); return; }
    const errs = treeErrors(cond, true);
    if (errs.length) { setErr(errs[0]); return; }
    setSaving(true);
    setErr('');
    try {
      const body = { name: name.trim(), strict, condition };
      if (team) await api.updateTeam(team.id, body); else await api.createTeam(body);
      await reloadTeams();
      toast({ title: team ? 'Team saved' : 'Team created', body: name.trim(), tone: 'ok' });
      onClose();
    } catch (e) {
      setErr(errorText(e));
      setSaving(false);
    }
  };

  return (
    <Modal open onClose={onClose} size="max-w-3xl">
      <ModalHead title={team ? `Edit ${team.name}` : 'Add team'} sub="Teams decide which events belong to them via a routing condition." onClose={onClose} />
      <div className="px-6 py-5 space-y-5 max-h-[70vh] overflow-y-auto">
        <div className="grid grid-cols-1 sm:grid-cols-[1fr_200px] gap-3">
          <div><label className="label">Team name</label><input className="inp w-full" maxLength={100} value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Team Rates" /></div>
          <div>
            <label className="label">Strict mode</label>
            <div className="h-9 flex items-center gap-2"><Toggle on={strict} onChange={() => setStrict(!strict)} label="Strict mode" /><span className="text-[13px] text-gray-500">{strict ? 'On' : 'Off'}</span></div>
          </div>
        </div>
        <p className="hint -mt-2">{strict ? 'Strict: fields must match their exact path and type.' : 'Flexible: a field name is also found when it sits at a different place in the event, and numeric strings compare as numbers.'}</p>
        <div>
          <label className="label">Routing condition</label>
          <ConditionBuilder value={cond} onChange={setCond} show={show} strict={strict} />
          <div className="mt-2 text-[13px] text-gray-500">
            {!condition ? 'Without a condition no events are routed to this team.'
              : count ? <><b className="font-semibold text-gray-900">{fmtNum(count.matched)}</b> of the last {fmtNum(count.checked)} events would route here</>
                : 'Counting matching events…'}
          </div>
        </div>
      </div>
      <ModalFoot error={err}>
        <button className="btn btn-white" onClick={onClose}>Cancel</button>
        <button className="btn btn-dark" onClick={save} disabled={saving}>{saving && <Loader className="w-4 h-4 spin" />}{team ? 'Save team' : 'Create team'}</button>
      </ModalFoot>
    </Modal>
  );
}
