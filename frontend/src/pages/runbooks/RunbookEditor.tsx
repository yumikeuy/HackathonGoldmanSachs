/**
 * New / edit runbook (large modal like the rule editor, opened from the Runbooks page).
 *
 * Who may save — the server enforces the same (docs/security.md, TeamScopeInterceptor):
 *   ADMIN    — any runbook, any scope including global;
 *   OPERATOR — runbooks of their own team; the scope is fixed to that team, global and other teams' runbooks open
 *              read-only;
 *   VIEWER   — looks only: every field is disabled and there is no save button.
 * Files over 10 MB are refused in the browser before uploading: the server answers 413 but closes the connection, so
 * the browser would otherwise only see a network error.
 */
import { useRef, useState } from 'react';
import { Download, Info, Loader, Lock, Upload } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ConditionBuilder } from '../../components/ConditionBuilder';
import { FileGlyph, Modal, ModalHead, Section, cx } from '../../components/ui';
import { fromEditor, toEditor, treeErrors, type EdGroup } from '../../utils/condition';
import { fmtBytes } from '../../utils/format';
import { canEditRunbook, usePermissions } from '../../auth/permissions';

type Mode = 'cond' | 'override';

const openings = new WeakMap<object, number>();
let openingSeq = 0;

export function RunbookEditor() {
  const { runbookEditor, closeRunbookEditor } = useStore();
  if (!runbookEditor) return null;
  // Remount once per opening (each opening is a new state object) so the form starts fresh.
  if (!openings.has(runbookEditor)) openings.set(runbookEditor, ++openingSeq);
  return <RunbookEditorInner key={openings.get(runbookEditor)} onClose={closeRunbookEditor} />;
}

function RunbookEditorInner({ onClose }: { onClose: () => void }) {
  const perms = usePermissions();
  const { runbookEditor, teams, rules, teamName, reloadRunbooks } = useStore();
  const toast = useToast();
  const st = runbookEditor!;
  const runbook = st.runbook ?? null;
  const isNew = !runbook;
  const editable = isNew ? perms.manageRunbooks : canEditRunbook(perms, runbook);

  const [file, setFile] = useState<File | null>(st.file ?? null);
  // Operators can only place runbooks in their own team; administrators default to global.
  const [teamId, setTeamId] = useState<number | null>(runbook ? runbook.teamId ?? null : perms.admin ? null : perms.teamId);
  const [mode, setMode] = useState<Mode>(runbook && !runbook.condition ? 'override' : 'cond');
  const [cond, setCond] = useState<EdGroup>(() => toEditor(runbook?.condition ?? null));
  const [show, setShow] = useState(false);
  const [progress, setProgress] = useState<number | null>(null);
  const [saveErr, setSaveErr] = useState('');
  const [drag, setDrag] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const team = teams.find(t => t.id === teamId);
  const usedBy = runbook ? rules.filter(r => r.overrideRunbookId === runbook.id) : [];
  const saving = progress != null;

  const pick = (f: File | undefined) => {
    if (!f) return;
    if (f.size > api.MAX_RUNBOOK_BYTES) {
      toast({ title: 'File is too large', body: `${f.name} is ${fmtBytes(f.size)}. The limit is 10 MB.`, tone: 'err' });
      return;
    }
    setFile(f);
  };

  const save = async () => {
    if (!editable || saving) return;
    setShow(true);
    const errs: string[] = [];
    if (isNew && !file) errs.push('Choose the file to upload');
    if (!perms.admin && teamId == null) errs.push('Choose your team');
    if (mode === 'cond') {
      if (!cond.c.length) errs.push('Add a condition, or choose “Only when a rule picks it”');
      errs.push(...treeErrors(cond, true));
    }
    if (errs.length) { setSaveErr(errs[0]); return; }

    const condition = mode === 'cond' ? fromEditor(cond) : null;
    setSaveErr('');
    setProgress(0);
    try {
      const form = { file, teamId, condition };
      const saved = runbook ? await api.updateRunbook(runbook.id, form, setProgress) : await api.createRunbook(form, setProgress);
      await reloadRunbooks();
      toast({
        title: isNew ? 'Runbook uploaded' : 'Runbook saved',
        body: condition ? `${saved.fileName} will be attached to matching new alerts.` : `${saved.fileName} can now be picked by a rule (“Always attach”).`,
        tone: 'ok',
      });
      onClose();
    } catch (e) {
      setProgress(null);
      setSaveErr(errorText(e));
    }
  };

  const readOnlyReason = editable ? null
    : !perms.manageRunbooks ? 'You can view runbooks; operators and administrators can change them.'
    : runbook?.teamId == null ? 'Global runbooks apply to every team, so only administrators can change them.'
    : 'This runbook belongs to another team.';

  return (
    <Modal open onClose={onClose} size="max-w-[1080px]">
      <div className="flex flex-col max-h-[calc(100vh-48px)]">
        <ModalHead
          title={isNew ? 'New runbook' : editable ? 'Edit runbook' : 'Runbook'}
          sub="Runbooks are stored as uploaded and attached to new alerts as a link — shown, never executed."
          onClose={onClose}
        />
        {readOnlyReason && (
          <div className="mx-6 mt-4 rounded-lg border border-gray-200 bg-gray-50 px-3 py-2.5 text-[13px] text-gray-600 flex gap-2">
            <Lock className="w-4 h-4 shrink-0 mt-0.5" />
            <div>Read-only. {readOnlyReason}</div>
          </div>
        )}

        <div className="flex-1 min-h-0 grid lg:grid-cols-[minmax(0,1fr)_340px]">
          <fieldset disabled={!editable || saving} className="min-w-0 overflow-y-auto px-6 py-6 space-y-8">
            <Section title="File" sub="Any format — PDF, Markdown, Word, text, YAML … — up to 10 MB.">
              {file || runbook ? (
                <div className="flex items-center gap-3 rounded-xl border border-gray-200 px-4 py-3">
                  <FileGlyph name={file?.name ?? runbook!.fileName} />
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-gray-900 truncate">{file?.name ?? runbook!.fileName}</div>
                    <div className="text-xs text-gray-500">{file ? `${fmtBytes(file.size)}${runbook ? ' · replaces the current file' : ''}` : `Current file · #${runbook!.id}`}</div>
                  </div>
                  <div className="ml-auto flex gap-1">
                    {runbook && !file && (
                      // A link, not a button: the fieldset disables its buttons for read-only users, who must still open the file.
                      <a className="btn btn-white btn-sm" href={runbook.fileUrl} target="_blank" rel="noreferrer"
                         onClick={e => { e.preventDefault(); api.openRunbook(runbook.id).catch(err => toast({ title: 'Could not open the runbook', body: errorText(err), tone: 'err' })); }}>
                        <Download className="w-3.5 h-3.5" />Open
                      </a>
                    )}
                    {editable && (
                      <button type="button" className="btn btn-white btn-sm" onClick={() => fileInput.current?.click()}>
                        <Upload className="w-3.5 h-3.5" />{file ? 'Choose another' : 'Replace file'}
                      </button>
                    )}
                  </div>
                </div>
              ) : (
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => fileInput.current?.click()}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') fileInput.current?.click(); }}
                  onDragOver={e => { e.preventDefault(); setDrag(true); }}
                  onDragLeave={() => setDrag(false)}
                  onDrop={e => { e.preventDefault(); setDrag(false); pick(e.dataTransfer.files[0]); }}
                  className={cx('dropzone cursor-pointer rounded-xl border-2 border-dashed border-gray-300 bg-white px-6 py-8 text-center transition hover:border-gray-400', drag && 'drag')}
                >
                  <div className="mx-auto h-10 w-10 rounded-full bg-gray-100 grid place-items-center"><Upload className="w-5 h-5 text-gray-600" /></div>
                  <div className="mt-3 text-sm font-medium text-gray-900">Drop a file here, or <span className="underline underline-offset-2">browse</span></div>
                </div>
              )}
              <input ref={fileInput} type="file" className="hidden" onChange={e => { pick(e.target.files?.[0]); e.target.value = ''; }} />
            </Section>

            <Section title="Scope" sub="A team's runbooks are tried before global ones.">
              <div className="grid grid-cols-1 sm:grid-cols-[260px_1fr] gap-3 items-center">
                <select className="inp w-full" value={teamId ?? ''} disabled={!perms.admin}
                        onChange={e => setTeamId(e.target.value ? +e.target.value : null)}>
                  {(perms.admin || teamId == null) && <option value="">Global (all teams)</option>}
                  {teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                  {teamId != null && !teams.some(t => t.id === teamId) && <option value={teamId}>{teamName(teamId) ?? `Team #${teamId}`}</option>}
                </select>
                {!perms.admin && editable && <p className="hint">You can add runbooks to your own team. Global runbooks are managed by administrators.</p>}
              </div>
            </Section>

            <Section title="When to attach" sub="Picked once, when a new alert opens.">
              <div className="seg flex-wrap">
                <button type="button" className={cx(mode === 'cond' && 'on')} onClick={() => setMode('cond')}>When conditions match</button>
                <button type="button" className={cx(mode === 'override' && 'on')} onClick={() => setMode('override')}>Only when a rule picks it</button>
              </div>
              {mode === 'cond' ? (
                <>
                  <ConditionBuilder value={cond} onChange={setCond} show={show} strict={team?.strict} />
                  <p className="hint">{team ? `${team.name}'s ${team.strict ? 'strict' : 'flexible'} matching applies.` : 'Global runbooks use flexible matching.'}</p>
                </>
              ) : (
                <div className="rounded-xl border border-dashed border-gray-300 p-4 text-[13px] text-gray-500">
                  This file is attached only by rules that pick it with the “Always attach” setting.
                </div>
              )}
            </Section>
          </fieldset>

          <aside className="border-t lg:border-t-0 lg:border-l border-gray-200 bg-gray-50 overflow-y-auto px-6 py-6 space-y-5">
            <div>
              <h4 className="text-[15px] font-semibold text-gray-900 flex items-center gap-2"><Info className="w-4 h-4 text-gray-500" />How a runbook is picked</h4>
              <ol className="mt-2 space-y-1.5 text-[13px] text-gray-600 list-decimal pl-5">
                <li>The rule's “Always attach” runbook, if it has one.</li>
                <li>Otherwise the alert team's first runbook whose conditions match the event.</li>
                <li>Otherwise the first matching global runbook.</li>
              </ol>
              <p className="mt-2 text-xs text-gray-400">Ties go to the older runbook. Unrouted alerts get no runbook.</p>
            </div>
            {runbook && (
              <div>
                <div className="text-xs font-medium text-gray-500 mb-1">Always attached by</div>
                {usedBy.length
                  ? <ul className="space-y-1">{usedBy.map(r => <li key={r.id} className="text-[13px] text-gray-700">“{r.name}” <span className="text-gray-400">· {teamName(r.teamId)}</span></li>)}</ul>
                  : <div className="text-[13px] text-gray-400">No rule picks this runbook explicitly.</div>}
              </div>
            )}
          </aside>
        </div>

        <div className="flex flex-wrap items-center gap-2 px-6 py-3 border-t border-gray-200 bg-white rounded-b-xl">
          <div className="min-w-0 flex-1">
            {saving
              ? <div className="h-1.5 rounded-full bg-gray-100 overflow-hidden"><div className="bar-fill h-full bg-gray-900 rounded-full" style={{ width: `${progress}%` }} /></div>
              : <div className="text-[13px] text-red-600 truncate" title={saveErr}>{saveErr}</div>}
          </div>
          <button className="btn btn-white" onClick={onClose}>{editable ? 'Cancel' : 'Close'}</button>
          {editable && (
            <button className="btn btn-dark" onClick={save} disabled={saving}>
              {saving ? <Loader className="w-4 h-4 spin" /> : <Upload className="w-4 h-4" />}{isNew ? 'Upload runbook' : 'Save changes'}
            </button>
          )}
        </div>
      </div>
    </Modal>
  );
}
