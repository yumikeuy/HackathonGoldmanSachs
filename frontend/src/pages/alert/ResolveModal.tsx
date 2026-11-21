/**
 * "Resolve this alert?" with an optional fix guide. The file is uploaded as a runbook of the alert's team
 * (`POST /api/runbooks`, ≤ 10 MB checked in the browser) and — if chosen — set as the rule's `overrideRunbookId`,
 * so the next alerts of that rule link to it.
 */
import { useRef, useState } from 'react';
import { Check, Loader, Upload, X } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { FileGlyph, Modal, ModalFoot, ModalHead, cx } from '../../components/ui';
import { fmtBytes } from '../../utils/format';
import { ruleToInput } from '../../utils/rules';
import type { AlertSummary } from '../../types';
import { usePermissions } from '../../auth/permissions';

export function ResolveModal({ alert, onClose }: { alert: AlertSummary | null; onClose: () => void }) {
  const { admin } = usePermissions();
  const { rules, upsertAlert, reloadRunbooks, reloadRules } = useStore();
  const toast = useToast();
  const [file, setFile] = useState<File | null>(null);
  const [attachToRule, setAttachToRule] = useState(true);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [drag, setDrag] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  if (!alert) return null;
  const rule = rules.find(r => r.id === alert.ruleId);

  const pick = (f: File | undefined) => {
    if (!f) return;
    if (f.size > api.MAX_RUNBOOK_BYTES) { setErr(`${f.name} is ${fmtBytes(f.size)}. The limit is 10 MB.`); return; }
    setErr('');
    setFile(f);
  };

  const close = () => { setFile(null); setErr(''); setBusy(false); onClose(); };

  const confirm = async () => {
    setBusy(true);
    setErr('');
    try {
      const updated = await api.resolveAlert(alert.id);
      upsertAlert(updated);
      let note = '';
      if (file) {
        const rb = await api.createRunbook({ file, teamId: alert.teamId ?? null, condition: null });
        note = ' · runbook saved';
        if (rule && attachToRule) {
          await api.updateRule(rule.id, { ...ruleToInput(rule), overrideRunbookId: rb.id });
          note = ` · runbook attached to “${rule.name}”`;
          reloadRules();
        }
        reloadRunbooks();
      }
      toast({ title: 'Alert resolved', body: alert.title + note, tone: 'ok' });
      close();
    } catch (e) {
      setErr(errorText(e));
      setBusy(false);
    }
  };

  return (
    <Modal open onClose={close} size="max-w-md">
      <ModalHead title="Resolve this alert?" sub="This closes the alert and stops further grouping into it." onClose={close} />
      <div className="px-6 py-4 space-y-4">
        <div className="text-sm text-gray-600">Mark <b className="font-medium text-gray-900">{alert.title}</b> as resolved.</div>
        {admin && <div>
          <label className="label">Attach a runbook <span className="text-gray-400 font-normal">(optional)</span></label>
          {file ? (
            <div className="flex items-center gap-3 rounded-xl border border-gray-200 p-3">
              <FileGlyph name={file.name} />
              <div className="min-w-0"><div className="text-sm font-medium text-gray-900 truncate">{file.name}</div><div className="text-[13px] text-gray-500">{fmtBytes(file.size)}</div></div>
              <button className="ml-auto btn btn-ghost btn-icon" onClick={() => setFile(null)} aria-label="Remove file"><X className="w-4 h-4" /></button>
            </div>
          ) : (
            <div
              onClick={() => input.current?.click()}
              onDragOver={e => { e.preventDefault(); setDrag(true); }}
              onDragLeave={() => setDrag(false)}
              onDrop={e => { e.preventDefault(); setDrag(false); pick(e.dataTransfer.files[0]); }}
              className={cx('dropzone cursor-pointer rounded-xl border-2 border-dashed border-gray-300 bg-white px-4 py-6 text-center transition hover:border-gray-400', drag && 'drag')}
            >
              <div className="mx-auto h-9 w-9 rounded-full bg-gray-100 grid place-items-center"><Upload className="w-4 h-4 text-gray-600" /></div>
              <div className="mt-2 text-[13px] font-medium text-gray-900">Drop a fix guide here, or <span className="underline underline-offset-2">browse</span></div>
              <div className="mt-0.5 text-xs text-gray-500">Any format, up to 10 MB. Saved for next time.</div>
            </div>
          )}
          <input ref={input} type="file" className="hidden" onChange={e => { pick(e.target.files?.[0]); e.target.value = ''; }} />
          {file && rule && (
            <label className="mt-3 flex items-start gap-2 text-[13px] text-gray-600">
              <input type="checkbox" className="mt-0.5 rounded border-gray-300" checked={attachToRule} onChange={e => setAttachToRule(e.target.checked)} />
              <span>Always attach it to new alerts of rule <b className="font-medium text-gray-900">{rule.name}</b></span>
            </label>
          )}
        </div>}
      </div>
      <ModalFoot error={err}>
        <button className="btn btn-white" onClick={close}>Cancel</button>
        <button className="btn btn-dark" disabled={busy} onClick={confirm}>{busy ? <Loader className="w-4 h-4 spin" /> : <Check className="w-4 h-4" />}Resolve alert</button>
      </ModalFoot>
    </Modal>
  );
}
