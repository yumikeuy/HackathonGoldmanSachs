/**
 * Runbooks: files of any format attached to alerts by condition (or by a rule's override). Like the Rules page: the
 * list with a scope filter, "New runbook" and a click on a row open the runbook editor ({@link RunbookEditor}).
 *
 * Administrators manage every runbook; operators the runbooks of their own team (global and other teams' runbooks are
 * read-only for them); viewers only look. A file dropped on the page opens the editor with that file.
 */
import { useMemo, useState, type DragEvent } from 'react';
import { BookOpen, Download, Plus, Trash2 } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { CondHuman, ConfirmModal, Empty, FileGlyph, PageIntro, PageWrap, Scope, Search, Spinner, cx } from '../../components/ui';
import { fmtBytes } from '../../utils/format';
import type { Runbook } from '../../types';
import { canEditRunbook, usePermissions } from '../../auth/permissions';

type ScopeFilter = 'all' | 'global' | number;

export function RunbooksPage() {
  const perms = usePermissions();
  const { runbooks, rules, teams, configLoaded, teamName, reloadRunbooks, reloadRules, openRunbookEditor } = useStore();
  const toast = useToast();
  const [q, setQ] = useState('');
  const [scope, setScope] = useState<ScopeFilter>('all');
  const [deleting, setDeleting] = useState<Runbook | null>(null);
  const [busy, setBusy] = useState(false);
  const [drag, setDrag] = useState(false);

  const list = useMemo(() => {
    const s = q.toLowerCase();
    return runbooks
      .filter(rb => scope === 'all' || (scope === 'global' ? rb.teamId == null : rb.teamId === scope))
      .filter(rb => !s || rb.fileName.toLowerCase().includes(s))
      .sort((a, b) => b.id - a.id);
  }, [runbooks, q, scope]);

  const openNew = (file?: File) => {
    if (file && file.size > api.MAX_RUNBOOK_BYTES) {
      toast({ title: 'File is too large', body: `${file.name} is ${fmtBytes(file.size)}. The limit is 10 MB.`, tone: 'err' });
      return;
    }
    openRunbookEditor(file ? { file } : {});
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
  // Dropping a file anywhere on the page opens the editor with it (only for those who may add runbooks).
  const dropHandlers = perms.manageRunbooks ? {
    onDragOver: (e: DragEvent) => { e.preventDefault(); setDrag(true); },
    onDragLeave: () => setDrag(false),
    onDrop: (e: DragEvent) => { e.preventDefault(); setDrag(false); openNew(e.dataTransfer.files[0]); },
  } : {};

  return (
    <PageWrap>
      <div {...dropHandlers}>
        <PageIntro
          right={
            <>
              <select className="inp" value={scope} onChange={e => setScope(e.target.value === 'all' || e.target.value === 'global' ? e.target.value : +e.target.value)}>
                <option value="all">All scopes</option>
                <option value="global">Global</option>
                {teams.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
              <Search value={q} onChange={setQ} placeholder="Search files…" className="w-56" />
              {perms.manageRunbooks && <button className="btn btn-dark" onClick={() => openNew()}><Plus className="w-4 h-4" />New runbook</button>}
            </>
          }
        >
          Runbooks are files attached to alerts so the person on the other end knows what to do. They are stored as they are and shared as a link —
          shown, never executed.{perms.manageRunbooks && !perms.admin && ' You can manage your team’s runbooks; global ones are managed by administrators.'}
        </PageIntro>

        <div className={cx('card overflow-x-auto transition', drag && 'ring-2 ring-gray-900/20')}>
          <table className="w-full">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr><th className="th pl-5">Name</th><th className="th">Scope</th><th className="th">Attached when</th><th className="th pr-5" /></tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {!configLoaded ? <tr><td colSpan={4}><Spinner /></td></tr> : list.length ? list.map(rb => {
                const used = usedBy(rb.id);
                return (
                  <tr key={rb.id} className="hover:bg-gray-50 cursor-pointer" onClick={() => openRunbookEditor({ runbook: rb })}>
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
                      <div className="flex justify-end gap-1" onClick={e => e.stopPropagation()}>
                        <button className="btn btn-white btn-sm"
                                onClick={() => api.openRunbook(rb.id).catch(err => toast({ title: 'Could not open the runbook', body: errorText(err), tone: 'err' }))}>
                          <Download className="w-3.5 h-3.5" />Open
                        </button>
                        {canEditRunbook(perms, rb) && (
                          <button className="btn btn-ghost btn-sm w-7 px-0 hover:!text-red-600" title="Delete" onClick={() => setDeleting(rb)}><Trash2 className="w-3.5 h-3.5" /></button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              }) : (
                <tr><td colSpan={4}>
                  <Empty icon={BookOpen} title="No runbooks here yet"
                         body={perms.manageRunbooks ? 'Add one with “New runbook”, or drop a file on this page.' : 'Runbooks your team can use will appear here.'} />
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
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
