/** "Send test events" — `POST /api/dev/generate` through the real ingest path (exists only with DEV_GENERATOR_ENABLED). */
import { useState } from 'react';
import { Loader, Zap } from 'lucide-react';
import * as api from '../../api/endpoints';
import { ApiError, errorText } from '../../api/client';
import { useStore } from '../../state/store';
import { useToast } from '../../components/Toast';
import { Modal, ModalFoot, ModalHead, Seg } from '../../components/ui';

const EXAMPLE = `{
  "type": "TRADE_REJECTED",
  "desk": "rates",
  "instrument": "{{oneOf:EUR-IRS-10Y|USD-IRS-5Y}}",
  "trade": { "id": "T-{{seq}}", "notional": "{{randInt:1000000:50000000}}" }
}`;

export function GenerateModal({ open, source, onClose }: { open: boolean; source?: string; onClose: () => void }) {
  const { sources } = useStore();
  const toast = useToast();
  const active = sources.filter(s => s.active !== false);
  const [src, setSrc] = useState(source ?? active[0]?.name ?? '');
  const [count, setCount] = useState(100);
  const [rate, setRate] = useState(20);
  const [mode, setMode] = useState<'samples' | 'template'>('samples');
  const [template, setTemplate] = useState(EXAMPLE);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const [lastOpen, setLastOpen] = useState(false);

  // Reset the source when reopened for another row.
  if (open !== lastOpen) {
    setLastOpen(open);
    if (open) { setSrc(source ?? active[0]?.name ?? ''); setErr(''); }
  }

  const start = async () => {
    let tpl: unknown = undefined;
    if (mode === 'template') {
      try { tpl = JSON.parse(template); } catch { setErr('The template is not valid JSON'); return; }
    }
    if (!src) { setErr('Choose a source'); return; }
    setBusy(true);
    setErr('');
    try {
      const r = await api.generate({ source: src, count, ratePerSecond: rate, template: tpl });
      toast({ title: 'Generating events', body: `${r.count} events to ${src} at ${rate}/s`, tone: 'ok', icon: Zap });
      onClose();
    } catch (e) {
      setErr(e instanceof ApiError && e.status === 404 ? 'The generator is disabled on this server (DEV_GENERATOR_ENABLED=false).' : errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open={open} onClose={onClose} size="max-w-xl">
      <ModalHead title="Send test events" sub="Events go through the real ingest path (Kafka, field catalog) — exactly like posted events." onClose={onClose} />
      <div className="px-6 py-5 space-y-4">
        <div className="grid grid-cols-1 sm:grid-cols-[1fr_110px_110px] gap-3">
          <div>
            <label className="label">Source</label>
            <select className="inp w-full" value={src} onChange={e => setSrc(e.target.value)}>
              {active.length === 0 && <option value="">No active sources</option>}
              {active.map(s => <option key={s.id} value={s.name}>{s.name} ({s.type})</option>)}
            </select>
          </div>
          <div><label className="label">Count</label><input className="inp w-full" type="number" min={1} max={100000} value={count} onChange={e => setCount(Math.max(1, +e.target.value || 1))} /></div>
          <div><label className="label">Per second</label><input className="inp w-full" type="number" min={1} max={1000} value={rate} onChange={e => setRate(Math.max(1, Math.min(1000, +e.target.value || 1)))} /></div>
        </div>
        <div>
          <label className="label">Events</label>
          <Seg value={mode} onChange={setMode} options={[['samples', 'Built-in samples'], ['template', 'My template']]} />
        </div>
        {mode === 'template' && (
          <div>
            <textarea className="inp w-full h-44 py-2 font-mono text-[12px] leading-5" value={template} onChange={e => setTemplate(e.target.value)} spellCheck={false} />
            <p className="hint mt-1.5">
              Placeholders in string values: <code className="code">{'{{seq}}'}</code> <code className="code">{'{{uuid}}'}</code> <code className="code">{'{{now}}'}</code>{' '}
              <code className="code">{'{{randInt:MIN:MAX}}'}</code> <code className="code">{'{{oneOf:A|B}}'}</code>. A value that is exactly <code className="code">{'{{seq}}'}</code> or a randInt becomes a number.
            </p>
          </div>
        )}
        {mode === 'samples' && <p className="hint">Cycles through the sample events shipped with the service (trade rejections, failed workflows, CPU alerts, an unknown schema…).</p>}
      </div>
      <ModalFoot error={err}>
        <button className="btn btn-white" onClick={onClose}>Cancel</button>
        <button className="btn btn-dark" onClick={start} disabled={busy}>{busy ? <Loader className="w-4 h-4 spin" /> : <Zap className="w-4 h-4" />}Start</button>
      </ModalFoot>
    </Modal>
  );
}
