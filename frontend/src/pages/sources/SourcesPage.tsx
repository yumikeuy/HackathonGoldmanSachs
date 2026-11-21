/** Event sources: HTTP webhook, external Kafka topic, tailed log file; API keys for http sources; test events. */
import { useState } from 'react';
import { Copy, FileText, Globe, KeyRound, Loader, Pencil, Plus, Radio, Server, Trash2, Zap, type LucideIcon } from 'lucide-react';
import * as api from '../../api/endpoints';
import { errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { ConfirmModal, Empty, Modal, ModalFoot, ModalHead, PageIntro, PageWrap, Seg, Spinner, Toggle, cx } from '../../components/ui';
import { copyText, fmtDT } from '../../utils/format';
import { sourceToInput } from '../../utils/rules';
import { GenerateModal } from './GenerateModal';
import type { Source, SourceApiKey, SourceType } from '../../types';

const TYPE: Record<SourceType, { l: string; i: LucideIcon }> = {
  http: { l: 'HTTP', i: Globe },
  kafka: { l: 'Kafka', i: Server },
  file: { l: 'Log file', i: FileText },
};

const cfg = (s: Source) => (s.config ?? {}) as Record<string, unknown>;

function where(s: Source): string {
  if (s.type === 'http') return `POST ${location.origin}/api/ingest/${s.name}`;
  if (s.type === 'kafka') return `topic ${String(cfg(s).topic ?? '—')}`;
  return `tail ${String(cfg(s).path ?? '—')}`;
}

function SourceModal({ source, onClose }: { source: Source | 'new' | null; onClose: () => void }) {
  if (!source) return null;
  return <SourceModalInner key={source === 'new' ? 'new' : source.id} source={source === 'new' ? null : source} onClose={onClose} />;
}

function SourceModalInner({ source, onClose }: { source: Source | null; onClose: () => void }) {
  const { reloadSources } = useStore();
  const toast = useToast();
  const [name, setName] = useState(source?.name ?? '');
  const [type, setType] = useState<SourceType>(source?.type ?? 'http');
  const [topic, setTopic] = useState(String((source && cfg(source).topic) ?? ''));
  const [path, setPath] = useState(String((source && cfg(source).path) ?? '/data/logs/app.log'));
  const [active, setActive] = useState(source?.active ?? true);
  const [err, setErr] = useState('');
  const [saving, setSaving] = useState(false);

  const save = async () => {
    if (!name.trim()) { setErr('Give the source a name'); return; }
    if (type === 'kafka' && !topic.trim()) { setErr('Enter the Kafka topic to consume'); return; }
    if (type === 'file' && !path.trim()) { setErr('Enter the file path inside the backend container'); return; }
    const config = type === 'kafka' ? { topic: topic.trim() } : type === 'file' ? { path: path.trim() } : {};
    setSaving(true);
    setErr('');
    try {
      const body = { name: name.trim(), type, config, active };
      if (source) await api.updateSource(source.id, body); else await api.createSource(body);
      await reloadSources();
      toast({ title: source ? 'Source saved' : 'Source created', body: body.name, tone: 'ok' });
      onClose();
    } catch (e) {
      setErr(errorText(e));
      setSaving(false);
    }
  };

  return (
    <Modal open onClose={onClose} size="max-w-lg">
      <ModalHead title={source ? `Edit ${source.name}` : 'Add source'} sub="A new event shape needs no code change — just a source." onClose={onClose} />
      <div className="px-6 py-5 space-y-4">
        <div><label className="label">Name</label><input className="inp w-full font-mono" maxLength={100} value={name} onChange={e => setName(e.target.value)} placeholder="payments-monitoring" /></div>
        <div>
          <label className="label">Type</label>
          <Seg value={type} onChange={setType} options={(Object.keys(TYPE) as SourceType[]).map(t => { const Ic = TYPE[t].i; return [t, <><Ic className="w-3.5 h-3.5" />{TYPE[t].l}</>]; })} />
        </div>
        {type === 'http' && <p className="hint">Events arrive at <code className="code">POST /api/ingest/{name || '{name}'}</code>. Protect it with an API key from the list.</p>}
        {type === 'kafka' && (
          <div><label className="label">Topic</label><input className="inp w-full font-mono" value={topic} onChange={e => setTopic(e.target.value)} placeholder="workflow.events" /><p className="hint mt-1.5">The service consumes this external topic.</p></div>
        )}
        {type === 'file' && (
          <div>
            <label className="label">File path</label>
            <input className="inp w-full font-mono" value={path} onChange={e => setPath(e.target.value)} placeholder="/data/logs/app.log" />
            <p className="hint mt-1.5">Path inside the backend container (<code className="code">infra/logs</code> is mounted at <code className="code">/data/logs</code>). Each new line is one event: a JSON line as is, any other line as <code className="code">{'{"message": "<line>"}'}</code>.</p>
          </div>
        )}
        <div className="flex items-center gap-2"><Toggle on={active} onChange={() => setActive(!active)} label="Source active" /><span className="text-[13px] text-gray-600">{active ? 'Active — accepting events' : 'Inactive — events are refused'}</span></div>
      </div>
      <ModalFoot error={err}>
        <button className="btn btn-white" onClick={onClose}>Cancel</button>
        <button className="btn btn-dark" onClick={save} disabled={saving}>{saving && <Loader className="w-4 h-4 spin" />}{source ? 'Save source' : 'Create source'}</button>
      </ModalFoot>
    </Modal>
  );
}

export function SourcesPage() {
  const { sources, configLoaded, reloadSources } = useStore();
  const toast = useToast();
  const [edit, setEdit] = useState<Source | 'new' | null>(null);
  const [del, setDel] = useState<Source | null>(null);
  const [busy, setBusy] = useState(false);
  const [key, setKey] = useState<SourceApiKey | null>(null);
  const [gen, setGen] = useState<{ source?: string } | null>(null);

  const toggle = (s: Source) =>
    api.updateSource(s.id, { ...sourceToInput(s), active: !s.active })
      .then(() => reloadSources())
      .then(() => toast({ title: `${s.name} ${s.active ? 'deactivated' : 'activated'}`, tone: 'ok' }))
      .catch(e => toast({ title: 'Could not change the source', body: errorText(e), tone: 'err' }));

  const rotateKey = async (s: Source) => {
    try {
      setKey(await api.createApiKey(s.id));
      await reloadSources();
    } catch (e) {
      toast({ title: 'Could not create an API key', body: errorText(e), tone: 'err' });
    }
  };
  const removeKey = async (s: Source) => {
    try {
      await api.deleteApiKey(s.id);
      await reloadSources();
      toast({ title: 'API key removed', body: `${s.name} accepts events without a key again.`, tone: 'ok' });
    } catch (e) {
      toast({ title: 'Could not remove the API key', body: errorText(e), tone: 'err' });
    }
  };

  const doDelete = async () => {
    if (!del) return;
    setBusy(true);
    try {
      await api.deleteSource(del.id);
      await reloadSources();
      toast({ title: 'Source deleted', body: del.name, tone: 'ok' });
      setDel(null);
    } catch (e) {
      toast({ title: 'Could not delete the source', body: errorText(e), tone: 'err' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <PageWrap wide>
      <PageIntro right={<>
        <button className="btn btn-white" onClick={() => setGen({})} disabled={!sources.length}><Zap className="w-4 h-4" />Send test events</button>
        <button className="btn btn-dark" onClick={() => setEdit('new')}><Plus className="w-4 h-4" />Add source</button>
      </>}>
        Sources are where events come from: an HTTP webhook, an external Kafka topic or a tailed log file. Events of any JSON shape are accepted.
      </PageIntro>
      <div className="card overflow-x-auto">
        <table className="w-full">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr><th className="th pl-5">Source</th><th className="th">Type</th><th className="th">Receives from</th><th className="th">API key</th><th className="th">Created</th><th className="th">Active</th><th className="th pr-5" /></tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {!configLoaded ? <tr><td colSpan={7}><Spinner /></td></tr> : sources.length === 0 ? (
              <tr><td colSpan={7}><Empty icon={Radio} title="No sources yet" body="Add one, or run infra/seed/seed.sh for the demo set." /></td></tr>
            ) : sources.map(s => {
              const Ic = TYPE[s.type].i;
              return (
                <tr key={s.id} className={cx('hover:bg-gray-50', s.active === false && 'opacity-50')}>
                  <td className="td pl-5"><div className="font-mono text-[13px] font-medium text-gray-900">{s.name}</div></td>
                  <td className="td"><span className="inline-flex items-center gap-1.5 text-[13px] text-gray-700"><Ic className="w-3.5 h-3.5 text-gray-400" />{TYPE[s.type].l}</span></td>
                  <td className="td"><code className="code break-all">{where(s)}</code></td>
                  <td className="td whitespace-nowrap">
                    {s.type !== 'http' ? <span className="text-gray-300">—</span> : s.apiKeyRequired ? (
                      <span className="inline-flex items-center gap-1">
                        <span className="inline-flex items-center gap-1 rounded-md bg-emerald-50 px-1.5 py-0.5 text-xs font-medium text-emerald-700"><KeyRound className="w-3 h-3" />Required</span>
                        <button className="btn btn-ghost btn-sm" onClick={() => rotateKey(s)}>Rotate</button>
                        <button className="btn btn-ghost btn-sm hover:!text-red-600" onClick={() => removeKey(s)}>Remove</button>
                      </span>
                    ) : (
                      <button className="btn btn-white btn-sm" onClick={() => rotateKey(s)}><KeyRound className="w-3.5 h-3.5" />Generate</button>
                    )}
                  </td>
                  <td className="td text-[13px] text-gray-500 whitespace-nowrap">{fmtDT(s.createdAt)}</td>
                  <td className="td"><Toggle on={s.active !== false} onChange={() => toggle(s)} label="Source active" /></td>
                  <td className="td pr-5">
                    <div className="flex justify-end gap-1">
                      <button className="btn btn-white btn-sm" disabled={s.active === false} onClick={() => setGen({ source: s.name })}><Zap className="w-3.5 h-3.5" />Test</button>
                      <button className="btn btn-ghost btn-sm w-7 px-0" title="Edit" onClick={() => setEdit(s)}><Pencil className="w-3.5 h-3.5" /></button>
                      <button className="btn btn-ghost btn-sm w-7 px-0 hover:!text-red-600" title="Delete" onClick={() => setDel(s)}><Trash2 className="w-3.5 h-3.5" /></button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <SourceModal source={edit} onClose={() => setEdit(null)} />
      <GenerateModal open={!!gen} source={gen?.source} onClose={() => setGen(null)} />
      <ConfirmModal open={!!del} title="Delete this source?" body={<><b className="text-gray-900 font-medium">{del?.name}</b> stops accepting events.</>} label="Delete source" busy={busy} onConfirm={doDelete} onClose={() => setDel(null)} />
      <Modal open={!!key} onClose={() => setKey(null)} size="max-w-lg">
        <ModalHead title="API key created" sub="Shown only once — store it in the sending system now." onClose={() => setKey(null)} />
        <div className="px-6 py-5 space-y-3">
          <div className="flex items-center gap-2">
            <code className="code flex-1 break-all !text-[13px] py-2">{key?.apiKey}</code>
            <button className="btn btn-white btn-sm" onClick={async () => toast(await copyText(key?.apiKey ?? '') ? { title: 'Key copied', tone: 'ok', icon: Copy } : { title: 'Clipboard not available here', tone: 'warn' })}><Copy className="w-3.5 h-3.5" />Copy</button>
          </div>
          <p className="hint">Send it as header <code className="code">{key?.header}: …</code> with every <code className="code">POST /api/ingest/{key?.sourceName}</code>. Without it the source answers 401.</p>
        </div>
        <ModalFoot><button className="btn btn-dark" onClick={() => setKey(null)}>Done</button></ModalFoot>
      </Modal>
    </PageWrap>
  );
}
