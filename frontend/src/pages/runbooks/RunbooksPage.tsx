/**
 * Runbooks: files of any format attached to alerts by condition (or by a rule's override).
 * Files over 10 MB are refused in the browser before uploading: the server answers 413 but closes the connection,
 * so the browser would otherwise only see a network error.
 */
import { useMemo, useRef, useState } from 'react';
import { BookOpen, Download, Loader, Pencil, Trash2, Upload, X } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ConditionBuilder } from '../../components/ConditionBuilder';
import { CondHuman, ConfirmModal, Empty, FileGlyph, PageIntro, PageWrap, Scope, Search, Spinner, cx } from '../../components/ui';
import { fromEditor, toEditor, treeErrors, type EdGroup } from '../../utils/condition';
import { fmtBytes } from '../../utils/format';
import type { Runbook } from '../../types';
import { usePermissions } from '../../auth/permissions';

interface Stage {
  runbook: Runbook | null;    // null — new upload
  file: File | null;          // new upload: required; edit: optional replacement
  teamId: number | null;
  mode: 'cond' | 'override';
  cond: EdGroup;
  show: boolean;
  progress: number | null;
  error: string;
}

export function RunbooksPage() {
  const { admin } = usePermissions();
  const { runbooks, rules, teams, configLoaded, teamName, reloadRunbooks, reloadRules } = useStore();
  const toast = useToast();
  const [q, setQ] = useState('');
  const [stage, setStage] = useState<Stage | null>(null);
  const [drag, setDrag] = useState(false);
  const [deleting, setDeleting] = useState<Runbook | null>(null);
  const [busy, setBusy] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const replaceInput = useRef<HTMLInputElement>(null);

  const list = useMemo(
    () => runbooks.filter(rb => !q || rb.fileName.toLowerCase().includes(q.toLowerCase())).sort((a, b) => b.id - a.id),
    [runbooks, q],
  );

  const tooBig = (f: File) => {
    if (f.size <= api.MAX_RUNBOOK_BYTES) return false;
    toast({ title: 'File is too large', body: `${f.name} is ${fmtBytes(f.size)}. The limit is 10 MB.`, tone: 'err' });
    return true;
  };

  const stageNew = (f: File | undefined) => {
    if (!f || tooBig(f)) return;
    setStage({ runbook: null, file: f, teamId: null, mode: 'cond', cond: toEditor(null), show: false, progress: null, error: '' });
  };

  const stageEdit = (rb: Runbook) => {
    setStage({ runbook: rb, file: null, teamId: rb.teamId ?? null, mode: rb.condition ? 'cond' : 'override', cond: toEditor(rb.condition ?? null), show: false, progress: null, error: '' });
    document.querySelector('main')?.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const submit = async () => {
    if (!stage || stage.progress != null) return;
    if (stage.mode === 'cond') {
      const errs = treeErrors(stage.cond, true);
      if (!stage.cond.c.length) errs.unshift('Add a condition, or choose “Only when a rule picks it”');
      if (errs.length) { setStage({ ...stage, show: true, error: errs[0] }); return; }
    }
    const condition = stage.mode === 'cond' ? fromEditor(stage.cond) : null;
    setStage({ ...stage, progress: 0, error: '' });
    const onProgress = (p: number) => setStage(s => (s ? { ...s, progress: p } : s));
    try {
      const form = { file: stage.file, teamId: stage.teamId, condition };
      const rb = stage.runbook ? await api.updateRunbook(stage.runbook.id, form, onProgress) : await api.createRunbook(form, onProgress);
      await reloadRunbooks();
      setStage(null);
      toast({
        title: stage.runbook ? 'Runbook saved' : 'Runbook uploaded',
        body: condition ? `${rb.fileName} will be attached to matching new alerts.` : `${rb.fileName} can now be picked by a rule (“Always attach”).`,
        tone: 'ok',
      });
    } catch (e) {
      setStage(s => (s ? { ...s, progress: null, error: errorText(e) } : s));
    }
  };

  const del = async () => {
    if (!deleting) return;
    setBusy(true);
    try {
      await api.deleteRunbook(deleting.id);
      await Promise.all([reloadRunbooks(), reloadRules()]);
      toast({ title: 'Runbook deleted', body: deleting.fileName, tone: 'ok' });
      setDeleting(null);
    } catch (e) {
      toast({ title: 'Could not delete the runbook', body: errorText(e), tone: 'err' });
    } finally {
      setBusy(false);
    }
  };

  const usedBy = (id: number) => rules.filter(r => r.overrideRunbookId === id);
  const team = teams.find(t => t.id === stage?.teamId);

  return (
    <PageWrap>
      <PageIntro>
        Runbooks are files attached to alerts so the person on the other end knows what to do. They are stored as they are and shared as a link —
        shown, never executed.
      </PageIntro>

      {admin && !stage && (
        <div
          onClick={() => fileInput.current?.click()}
          onDragOver={e => { e.preventDefault(); setDrag(true); }}
          onDragLeave={() => setDrag(false)}
          onDrop={e => { e.preventDefault(); setDrag(false); stageNew(e.dataTransfer.files[0]); }}
          className={cx('dropzone cursor-pointer rounded-xl border-2 border-dashed border-gray-300 bg-white px-6 py-9 text-center transition hover:border-gray-400', drag && 'drag')}
        >
          <div className="mx-auto h-11 w-11 rounded-full bg-gray-100 grid place-items-center"><Upload className="w-5 h-5 text-gray-600" /></div>
          <div className="mt-3 text-sm font-medium text-gray-900">Drop a file here, or <span className="underline underline-offset-2">browse</span></div>
          <div className="mt-1 text-[13px] text-gray-500">PDF, Markdown, Word, text or any other format, up to 10 MB</div>
        </div>
      )}
      <input ref={fileInput} type="file" className="hidden" onChange={e => { stageNew(e.target.files?.[0]); e.target.value = ''; }} />
      <input ref={replaceInput} type="file" className="hidden" onChange={e => { const f = e.target.files?.[0]; if (f && !tooBig(f) && stage) setStage({ ...stage, file: f }); e.target.value = ''; }} />

      {stage && (
        <div className="card p-5 fade-in">
          <div className="flex items-center gap-3">
            <FileGlyph name={stage.file?.name ?? stage.runbook?.fileName ?? 'file'} />
            <div className="min-w-0">
              <div className="text-sm font-medium text-gray-900 truncate">{stage.file?.name ?? stage.runbook?.fileName}</div>
              <div className="text-xs text-gray-500">
                {stage.file ? fmtBytes(stage.file.size) : 'Current file'}
                {stage.runbook && <> · <button className="underline underline-offset-2 hover:text-gray-900" onClick={() => replaceInput.current?.click()}>{stage.file ? 'choose another file' : 'replace file'}</button></>}
              </div>
            </div>
            <span className="ml-auto text-xs font-medium text-gray-500">{stage.runbook ? 'Editing' : 'New runbook'}</span>
            <button className="btn btn-ghost btn-icon" onClick={() => setStage(null)} aria-label="Cancel"><X className="w-5 h-5" /></button>
          </div>
          <div className="mt-5 grid grid-cols-1 md:grid-cols-[240px_1fr] gap-5">
            <div>
              <label className="label">Scope</label>
              <select className="inp w-full" value={stage.teamId ?? ''} onChange={e => setStage({ ...stage, teamId: e.target.value ? +e.target.value : null })}>
                <option value="">Global (all teams)</option>
                {teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
              <label className="label mt-4">Attach</label>
              <div className="seg flex-wrap">
                <button className={cx(stage.mode === 'cond' && 'on')} onClick={() => setStage({ ...stage, mode: 'cond' })}>When conditions match</button>
                <button className={cx(stage.mode === 'override' && 'on')} onClick={() => setStage({ ...stage, mode: 'override' })}>Only when a rule picks it</button>
              </div>
              {stage.mode === 'cond' && <p className="hint mt-3">{team ? `${team.name}'s ${team.strict ? 'strict' : 'flexible'} matching applies.` : 'Global runbooks use flexible matching.'}</p>}
            </div>
            <div>
              {stage.mode === 'cond' ? (
                <><label className="label">Conditions</label><ConditionBuilder value={stage.cond} onChange={c => setStage({ ...stage, cond: c })} show={stage.show} strict={team?.strict} /></>
              ) : (
                <div className="h-full rounded-xl border border-dashed border-gray-300 p-4 text-[13px] text-gray-500 grid place-items-center text-center">
                  Rules can attach this file with the “Always attach” setting.
                </div>
              )}
            </div>
          </div>
          {stage.error && <div className="mt-4 text-[13px] text-red-600">{stage.error}</div>}
          <div className="mt-5 flex items-center gap-3">
            <div className={cx('flex-1 h-1.5 rounded-full bg-gray-100 overflow-hidden', stage.progress == null && 'invisible')}>
              <div className="bar-fill h-full bg-gray-900 rounded-full" style={{ width: `${stage.progress ?? 0}%` }} />
            </div>
            <button className="btn btn-white" onClick={() => setStage(null)}>Cancel</button>
            <button className="btn btn-dark" onClick={submit} disabled={stage.progress != null}>
              {stage.progress != null ? <Loader className="w-4 h-4 spin" /> : <Upload className="w-4 h-4" />}{stage.runbook ? 'Save' : 'Upload'}
            </button>
          </div>
        </div>
      )}

      <div className="card overflow-x-auto mt-6">
        <div className="flex flex-wrap items-center gap-2 px-5 py-3 border-b border-gray-200">
          <span className="text-sm font-semibold text-gray-900">Files</span>
          <span className="text-[13px] text-gray-500">{list.length}</span>
          <div className="ml-auto"><Search value={q} onChange={setQ} placeholder="Search files…" className="w-56" /></div>
        </div>
        <table className="w-full">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><th className="th pl-5">Name</th><th className="th">Scope</th><th className="th">Attached when</th><th className="th pr-5" /></tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {!configLoaded ? <tr><td colSpan={4}><Spinner /></td></tr> : list.length ? list.map(rb => {
              const used = usedBy(rb.id);
              return (
                <tr key={rb.id} className="hover:bg-gray-50">
                  <td className="td pl-5">
                    <div className="flex items-center gap-3">
                      <FileGlyph name={rb.fileName} />
                      <div className="min-w-0"><div className="text-sm font-medium text-gray-900 truncate">{rb.fileName}</div><div className="text-xs text-gray-500">#{rb.id}</div></div>
                    </div>
                  </td>
                  <td className="td"><Scope teamId={rb.teamId} name={teamName(rb.teamId) ?? undefined} /></td>
                  <td className="td text-[13px] text-gray-600 leading-6 max-w-[420px]">
                    {rb.condition ? <CondHuman c={rb.condition} /> : <span className="text-gray-400">Only when a rule picks it</span>}
                    {used.length > 0 && <div className="text-xs text-gray-400">Always attached by {used.map(r => `“${r.name}”`).join(', ')}</div>}
                  </td>
                  <td className="td pr-5">
                    <div className="flex justify-end gap-1">
                      <a className="btn btn-white btn-sm" href={rb.fileUrl} target="_blank" rel="noreferrer"
                         onClick={e => { e.preventDefault(); api.openRunbook(rb.id).catch(err => toast({ title: 'Could not open the runbook', body: errorText(err), tone: 'err' })); }}><Download className="w-3.5 h-3.5" />Open</a>
                      {admin && <button className="btn btn-ghost btn-sm w-7 px-0" title="Edit" onClick={() => stageEdit(rb)}><Pencil className="w-3.5 h-3.5" /></button>}
                      {admin && <button className="btn btn-ghost btn-sm w-7 px-0 hover:!text-red-600" title="Delete" onClick={() => setDeleting(rb)}><Trash2 className="w-3.5 h-3.5" /></button>}
                    </div>
                  </td>
                </tr>
              );
            }) : (
              <tr><td colSpan={4}><Empty icon={BookOpen} title="No runbooks yet" body="Upload a file above." /></td></tr>
            )}
          </tbody>
        </table>
      </div>
      <ConfirmModal
        open={!!deleting}
        title="Delete this runbook?"
        body={<>
          <b className="text-gray-900 font-medium">{deleting?.fileName}</b> will no longer be attached to new alerts; alerts that link to it lose the link.
          {deleting && usedBy(deleting.id).length > 0 && ` ${usedBy(deleting.id).length} rule(s) that always attach it will pick automatically instead.`}
        </>}
        label="Delete runbook"
        busy={busy}
        onConfirm={del}
        onClose={() => setDeleting(null)}
      />
    </PageWrap>
  );
}
