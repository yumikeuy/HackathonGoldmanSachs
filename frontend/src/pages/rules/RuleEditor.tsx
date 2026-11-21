/**
 * New / edit rule (large modal, opened from Rules, the alert drawer and the Unrouted page).
 * The dry-run panel calls `POST /api/rules/dry-run` (last 1000 events) as you edit — once with the team (only events
 * routed to it count) and once without, to warn when the rule matches events the team's routing does not cover.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ChevronRight, FlaskConical, Info, Layers, Loader, Plus, X } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ConditionBuilder } from '../../components/ConditionBuilder';
import { ChipsInput } from '../../components/ChipsInput';
import { CHAN, CHANNELS, ErrorBox, JsonView, Modal, ModalHead, SEV, Section, SevBars, Toggle, cx } from '../../components/ui';
import { condLeaves, fromEditor, getPath, newGroup, renderTemplate, toEditor, treeErrors, type EdGroup } from '../../utils/condition';
import { fmtNum } from '../../utils/format';
import type { Channel, Condition, DryRunResult, GroupMode, RuleInput, Severity } from '../../types';
import { usePermissions } from '../../auth/permissions';

interface Draft {
  name: string; teamId: number | null; priority: number; active: boolean; assignedSeverity: Severity; channels: Channel[];
  groupByPaths: string[]; groupMode: GroupMode; dedupWindowSec: number; title: string; overrideRunbookId: number | null;
}

/** Matches every event (a missing field is "absent"): used to dry-run a rule without a condition. */
const MATCH_ALL: Condition = { path: '__match_all__', op: 'exists', value: false };

interface DryState { loading: boolean; error: string; team?: DryRunResult; all?: DryRunResult }

const openings = new WeakMap<object, number>();
let openingSeq = 0;

export function RuleEditor() {
  const { ruleEditor, closeRuleEditor } = useStore();
  if (!ruleEditor) return null;
  // Remount once per opening (each opening is a new state object) so the draft starts fresh.
  if (!openings.has(ruleEditor)) openings.set(ruleEditor, ++openingSeq);
  return <RuleEditorInner key={openings.get(ruleEditor)} onClose={closeRuleEditor} />;
}

function RuleEditorInner({ onClose }: { onClose: () => void }) {
  const { admin } = usePermissions();
  const { ruleEditor, teams, runbooks, reloadRules } = useStore();
  const toast = useToast();
  const navigate = useNavigate();
  const st = ruleEditor!;
  const src = { ...(st.rule ?? {}), ...(st.prefill ?? {}) } as Partial<RuleInput> & { id?: number };
  const isNew = !st.rule;

  const [d, setD] = useState<Draft>(() => ({
    name: src.name ?? '',
    teamId: src.teamId ?? null,
    priority: src.priority ?? 100,
    active: src.active ?? true,
    assignedSeverity: src.assignedSeverity ?? 'HIGH',
    channels: src.channels ?? ['EMAIL', 'UI'],
    groupByPaths: src.groupByPaths ?? [],
    groupMode: src.groupMode ?? 'while_open',
    dedupWindowSec: src.dedupWindowSec ?? 300,
    title: src.presentation?.title ?? '',
    overrideRunbookId: src.overrideRunbookId ?? null,
  }));
  const [cond, setCond] = useState<EdGroup>(() => toEditor(src.condition ?? null));
  const [resolve, setResolve] = useState<EdGroup | null>(() => (src.resolveCondition ? toEditor(src.resolveCondition) : null));
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState('');
  const [dry, setDry] = useState<DryState>({ loading: false, error: '' });
  const titleRef = useRef<HTMLInputElement>(null);

  const team = teams.find(t => t.id === d.teamId);
  const strict = team?.strict ?? false;
  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setD(x => ({ ...x, [k]: v }));

  const condition = useMemo(() => fromEditor(cond), [cond]);
  const resolveCondition = useMemo(() => (resolve ? fromEditor(resolve) : null), [resolve]);
  const condErrors = useMemo(() => treeErrors(cond, false), [cond]);

  const body: RuleInput = {
    name: d.name.trim(), teamId: d.teamId ?? 0, condition, priority: d.priority, active: d.active,
    assignedSeverity: d.assignedSeverity, channels: d.channels, groupByPaths: d.groupByPaths, groupMode: d.groupMode,
    dedupWindowSec: d.dedupWindowSec, presentation: { title: d.title.trim() || null }, resolveCondition,
    overrideRunbookId: d.overrideRunbookId,
  };

  /* ---------- dry-run (debounced, cancels the previous one) ---------- */
  const condKey = JSON.stringify(condition) + '|' + d.teamId;
  const abort = useRef<AbortController | null>(null);
  const runDry = async () => {
    if (treeErrors(cond, true).length) {
      setShow(true);
      setDry({ loading: false, error: treeErrors(cond, true)[0] });
      return;
    }
    abort.current?.abort();
    const ac = new AbortController();
    abort.current = ac;
    setDry(x => ({ ...x, loading: true, error: '' }));
    const c = condition ?? MATCH_ALL;
    try {
      const [teamRes, allRes] = await Promise.all([
        d.teamId ? api.dryRun({ condition: c, teamId: d.teamId, sampleSize: 50 }, ac.signal) : Promise.resolve(undefined),
        api.dryRun({ condition: c, strict, sampleSize: 50 }, ac.signal),
      ]);
      setDry({ loading: false, error: '', team: teamRes, all: allRes });
    } catch (e) {
      if ((e as Error).name !== 'AbortError') setDry({ loading: false, error: errorText(e) });
    }
  };
  useEffect(() => {
    if (condErrors.length) return;
    const t = setTimeout(runDry, 600);
    return () => clearTimeout(t);
  }, [condKey]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => abort.current?.abort(), []);

  const main = d.teamId ? dry.team : dry.all;
  const samples = (main?.samples ?? []) as unknown[];
  const groups = useMemo(() => {
    const m = new Map<string, number>();
    for (const p of samples) {
      const k = d.groupByPaths.map(g => { const v = getPath(p, g, strict); return v === undefined || v === null ? '∅' : String(v); }).join(' · ') || 'one group';
      m.set(k, (m.get(k) ?? 0) + 1);
    }
    return [...m].sort((a, b) => b[1] - a[1]);
  }, [samples, d.groupByPaths, strict]);

  const previewPayload = samples[0] ?? st.origin?.payload;
  const vars = [...new Set([...condLeaves(condition).map(l => l.path), ...d.groupByPaths])].filter(Boolean).slice(0, 8);

  const insertVar = (p: string) => {
    const i = titleRef.current;
    const ins = `{{${p}}}`;
    const s = i?.selectionStart ?? d.title.length;
    const e = i?.selectionEnd ?? s;
    set('title', d.title.slice(0, s) + ins + d.title.slice(e));
    setTimeout(() => i?.focus(), 0);
  };

  const save = async () => {
    setShow(true);
    const errs: string[] = [];
    if (!d.name.trim()) errs.push('Give the rule a name');
    if (!d.teamId) errs.push('Choose the team that owns this rule');
    if (!d.channels.length) errs.push('Pick at least one way to notify');
    if (!(d.dedupWindowSec >= 1)) errs.push('The window must be at least 1 second');
    errs.push(...treeErrors(cond, true));
    if (resolve) {
      if (!resolve.c.length) errs.push('The resolve condition is empty');
      errs.push(...treeErrors(resolve, true));
    }
    if (errs.length) { setSaveErr(errs[0]); return; }
    setSaving(true);
    setSaveErr('');
    try {
      const saved = isNew ? await api.createRule(body) : await api.updateRule(st.rule!.id, body);
      await reloadRules();
      toast({ title: isNew ? 'Rule created' : 'Rule saved', body: `“${saved.name}” applies to new events right away.`, tone: 'ok' });
      onClose();
      navigate('/rules');
    } catch (e) {
      setSaveErr(errorText(e));
      setSaving(false);
    }
  };

  const gmHelp = d.groupMode === 'while_open'
    ? 'While open: similar events keep adding to the same alert until it is resolved. The next event after that opens a new alert.'
    : `Per window: a new alert opens every ${d.dedupWindowSec} seconds, even if the previous one is still open.`;

  return (
    <Modal open onClose={onClose} size="max-w-[1180px]">
      <div className="flex flex-col max-h-[calc(100vh-48px)]">
        <ModalHead title={isNew ? 'New rule' : 'Edit rule'} sub="Changes apply to new events as soon as you save." onClose={onClose} />
        {st.origin && (
          <div className="mx-6 mt-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2.5 text-[13px] text-amber-800 flex gap-2">
            <Info className="w-4 h-4 shrink-0 mt-0.5" />
            <div>
              Prefilled from an event{st.origin.source ? <> sent by <b className="font-medium">{st.origin.source}</b></> : ''}.
              {st.origin.suggestedTeam ? <> Suggested team: <b className="font-medium">{st.origin.suggestedTeam}</b>.</> : ' That event matched no team, so pick a team below.'}
              {' '}Check that the team's routing also covers it — the dry-run on the right shows both.
            </div>
          </div>
        )}
        <div className="flex-1 min-h-0 grid lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="overflow-y-auto px-6 py-6 space-y-8">
            <Section title="Basics">
              <div className="grid grid-cols-1 sm:grid-cols-[1fr_200px_110px] gap-3">
                <div><label className="label">Name</label><input className="inp w-full" maxLength={200} value={d.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Rejected large trades" /></div>
                <div>
                  <label className="label">Team</label>
                  <select className="inp w-full" value={d.teamId ?? ''} onChange={e => set('teamId', e.target.value ? +e.target.value : null)}>
                    <option value="">Choose a team…</option>
                    {teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                  </select>
                </div>
                <div><label className="label">Priority</label><input className="inp w-full" type="number" value={d.priority} onChange={e => set('priority', +e.target.value || 0)} title="Lower runs first" /></div>
              </div>
              <div className="flex flex-wrap gap-x-8 gap-y-4">
                <div>
                  <label className="label">Severity</label>
                  <div className="seg">
                    {(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as Severity[]).map(s => (
                      <button key={s} type="button" className={cx(d.assignedSeverity === s && 'on', 'inline-flex items-center gap-1.5')} onClick={() => set('assignedSeverity', s)}><SevBars s={s} />{SEV[s].l}</button>
                    ))}
                  </div>
                </div>
                <div>
                  <label className="label">Notify via</label>
                  <div className="flex flex-wrap gap-1.5">
                    {CHANNELS.map(c => {
                      const Ic = CHAN[c].i;
                      const on = d.channels.includes(c);
                      return (
                        <button key={c} type="button" className={cx('pill', on && 'on')} onClick={() => set('channels', on ? d.channels.filter(x => x !== c) : [...d.channels, c])}>
                          <Ic className="w-3.5 h-3.5" />{CHAN[c].l}
                        </button>
                      );
                    })}
                  </div>
                </div>
                <div><label className="label">Active</label><div className="h-9 flex items-center"><Toggle on={d.active} onChange={() => set('active', !d.active)} label="Rule active" /></div></div>
              </div>
              {team && <p className="hint">{team.name} uses {strict ? 'strict mode: exact field paths and types only.' : 'flexible mode: a field name is also found at a different place in the event.'}</p>}
            </Section>

            <Section title="When should this rule fire?" sub="Build conditions on any field of the event. Leave empty to match every event routed to the team.">
              <ConditionBuilder value={cond} onChange={setCond} show={show} strict={strict} />
            </Section>

            <Section title="Grouping" sub="Events that share these fields are grouped into one alert with an event counter, instead of notifying for each one.">
              <div className="grid grid-cols-1 sm:grid-cols-[1fr_auto_150px] gap-3 items-start">
                <div><label className="label">Group by fields</label><ChipsInput values={d.groupByPaths} onChange={v => set('groupByPaths', v)} strict={strict} /></div>
                <div>
                  <label className="label">Deduplicate</label>
                  <div className="seg">
                    {([['while_open', 'While open'], ['per_window', 'Per window']] as Array<[GroupMode, string]>).map(([k, l]) => (
                      <button key={k} type="button" className={cx(d.groupMode === k && 'on')} onClick={() => set('groupMode', k)}>{l}</button>
                    ))}
                  </div>
                </div>
                <div><label className="label">Window (seconds)</label><input className="inp w-full" type="number" min={1} step={10} value={d.dedupWindowSec} onChange={e => set('dedupWindowSec', +e.target.value || 0)} /></div>
              </div>
              <p className="hint">{gmHelp}</p>
            </Section>

            <Section title="Alert title" sub="Use {{field}} to insert values from the event. Empty — the rule name.">
              <input ref={titleRef} className="inp w-full font-mono" maxLength={500} value={d.title} onChange={e => set('title', e.target.value)} placeholder="Rejected {{trade.id}} ({{trade.notional}})" />
              <div className="rounded-lg bg-gray-50 border border-gray-200 px-3 py-2 text-sm">
                <span className="text-xs text-gray-500 mr-2">Preview</span>
                <span className="text-gray-900 font-medium">
                  {d.title
                    ? previewPayload ? renderTemplate(d.title, previewPayload, strict) : <span className="text-gray-400 font-normal">No recent event matches yet</span>
                    : <span className="text-gray-400 font-normal">Uses the rule name{d.name ? `: ${d.name}` : ''}</span>}
                </span>
              </div>
              {vars.length > 0 && (
                <div className="flex flex-wrap gap-1">
                  <span className="text-xs text-gray-400 self-center mr-1">Insert:</span>
                  {vars.map(p => <button key={p} type="button" className="chip font-mono hover:bg-gray-200" onClick={() => insertVar(p)}>{`{{${p}}}`}</button>)}
                </div>
              )}
            </Section>

            <Section title="Auto-resolve" sub="Close the alert automatically when a recovery event for the same group arrives.">
              {resolve ? (
                <>
                  <ConditionBuilder value={resolve} onChange={setResolve} show={show} strict={strict} />
                  <button className="btn btn-ghost btn-sm hover:!text-red-600" onClick={() => setResolve(null)}><X className="w-3.5 h-3.5" />Remove auto-resolve</button>
                </>
              ) : (
                <button className="btn btn-white" onClick={() => setResolve(newGroup('and'))}><Plus className="w-4 h-4" />Add a resolve condition</button>
              )}
            </Section>

            <Section title="Runbook">
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 items-end">
                <div>
                  <label className="label">Always attach</label>
                  <select className="inp w-full" value={d.overrideRunbookId ?? ''} onChange={e => set('overrideRunbookId', e.target.value ? +e.target.value : null)}>
                    <option value="">Pick automatically by runbook conditions</option>
                    {runbooks.map(rb => <option key={rb.id} value={rb.id}>{rb.fileName} ({rb.teamId ? teams.find(t => t.id === rb.teamId)?.name ?? 'team' : 'Global'})</option>)}
                  </select>
                </div>
                <p className="hint pb-2">Automatic picks the team's matching runbook first, then a global one.</p>
              </div>
            </Section>
          </div>

          <aside className="border-t lg:border-t-0 lg:border-l border-gray-200 bg-gray-50 overflow-y-auto px-6 py-6 space-y-5">
            <div className="card p-4">
              <div className="flex items-center">
                <div className="text-[15px] font-semibold text-gray-900">Dry-run</div>
                {dry.loading && <Loader className="ml-2 w-4 h-4 spin text-gray-400" />}
              </div>
              <p className="mt-1 text-[13px] text-gray-500">Tests the conditions against the last {fmtNum(main?.checked ?? 1000)} events received. Nothing is created or sent. Updates as you edit.</p>
              <button className="mt-3 btn btn-white w-full" onClick={runDry} disabled={dry.loading}><FlaskConical className="w-4 h-4" />Run dry-run</button>
              <div className="mt-3">
                {dry.error ? <ErrorBox>{dry.error}</ErrorBox> : main && (
                  <div className="space-y-3 fade-in">
                    <div>
                      <div className="text-2xl font-semibold text-gray-900 tabular-nums">{fmtNum(main.matched)} <span className="text-sm font-normal text-gray-500">of {fmtNum(main.checked)} events matched</span></div>
                      <div className="mt-2 h-1.5 rounded-full bg-gray-100 overflow-hidden">
                        <div className="bar-fill h-full rounded-full bg-gray-900" style={{ width: `${main.checked ? (main.matched / main.checked) * 100 : 0}%` }} />
                      </div>
                    </div>
                    <dl className="grid grid-cols-2 gap-2">
                      <div className="rounded-lg bg-gray-50 border border-gray-200 px-3 py-2">
                        <dt className="text-xs text-gray-500">Matched by the rule</dt>
                        <dd className="mt-0.5 text-sm font-semibold text-gray-900">{fmtNum(dry.all?.matched ?? 0)}</dd>
                      </div>
                      <div className="rounded-lg bg-gray-50 border border-gray-200 px-3 py-2">
                        <dt className="text-xs text-gray-500">Also routed to the team</dt>
                        <dd className={cx('mt-0.5 text-sm font-semibold', dry.team && dry.all && dry.all.matched > 0 && dry.team.matched === 0 ? 'text-amber-600' : 'text-gray-900')}>
                          {d.teamId ? fmtNum(dry.team?.matched ?? 0) : <span className="font-normal text-gray-400">Pick a team</span>}
                        </dd>
                      </div>
                    </dl>
                    {main.matched > 0 && groups.length > 0 && (
                      <div className="text-[13px] text-emerald-700 flex items-center gap-1.5">
                        <Layers className="w-4 h-4" />{fmtNum(samples.length)} sampled events form {groups.length} alert group{groups.length === 1 ? '' : 's'}
                      </div>
                    )}
                    {team && dry.team && dry.all && dry.all.matched > 0 && dry.team.matched === 0 && (
                      <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[13px] text-amber-800">
                        These events match the rule but not {team.name}'s routing, so no alert would open. Update the team's routing in Teams &amp; Users.
                      </div>
                    )}
                    {groups.length > 0 && main.matched > 0 && (
                      <div>
                        <div className="text-xs font-medium text-gray-500 mb-1">Largest groups (in samples)</div>
                        {groups.slice(0, 4).map(([k, n]) => (
                          <div key={k} className="flex items-center gap-2 text-[13px] py-0.5"><span className="font-mono text-[12px] text-gray-700 truncate">{k}</span><span className="ml-auto text-gray-500">×{n}</span></div>
                        ))}
                      </div>
                    )}
                    {samples.length > 0 && (
                      <div>
                        <div className="text-xs font-medium text-gray-500 mb-1">Example alerts</div>
                        <div className="space-y-1.5">
                          {samples.slice(0, 4).map((p, i) => (
                            <div key={i} className="rounded-lg border border-gray-200 bg-white px-3 py-2">
                              <div className="text-[13px] font-medium text-gray-900 truncate">{d.title ? renderTemplate(d.title, p, strict) : d.name || 'Untitled'}</div>
                              <div className="text-xs text-gray-500 truncate font-mono">{JSON.stringify(p).slice(0, 80)}</div>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
            <details className="group">
              <summary className="cursor-pointer list-none flex items-center gap-1.5 text-[13px] text-gray-500 hover:text-gray-900">
                <ChevronRight className="w-4 h-4 transition group-open:rotate-90" />Show as JSON
              </summary>
              <div className="mt-2"><JsonView value={body} className="max-h-[420px] !text-[11.5px] bg-white" /></div>
            </details>
          </aside>
        </div>
        <div className="flex flex-wrap items-center gap-2 px-6 py-3 border-t border-gray-200 bg-white rounded-b-xl">
          <div className="text-[13px] text-red-600 min-w-0 flex-1 truncate" title={saveErr}>{saveErr || (show ? condErrors[0] ?? '' : '')}</div>
          <button className="btn btn-white" onClick={onClose}>Cancel</button>
          {admin && <button className="btn btn-dark" onClick={save} disabled={saving}>{saving && <Loader className="w-4 h-4 spin" />}{isNew ? 'Create rule' : 'Save changes'}</button>}
        </div>
      </div>
    </Modal>
  );
}
