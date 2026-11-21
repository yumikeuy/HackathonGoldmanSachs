import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { CircleCheck, CircleX, Info, Siren, TriangleAlert, X, type LucideIcon } from 'lucide-react';

export type Tone = 'info' | 'ok' | 'warn' | 'err' | 'crit';
export interface ToastInput {
  title: ReactNode;
  body?: ReactNode;
  tone?: Tone;
  icon?: LucideIcon;
  action?: { label: string; onClick: () => void };
}
interface ToastItem extends ToastInput { id: number; leaving?: boolean }

const TONES: Record<Tone, [LucideIcon, string]> = {
  info: [Info, 'text-gray-500'],
  ok: [CircleCheck, 'text-emerald-500'],
  warn: [TriangleAlert, 'text-amber-500'],
  err: [CircleX, 'text-red-500'],
  crit: [Siren, 'text-red-500'],
};

const Ctx = createContext<(t: ToastInput) => void>(() => {});

/** Bottom-right toasts (max 3), as in the prototype. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const seq = useRef(0);

  const remove = useCallback((id: number) => setItems(xs => xs.filter(x => x.id !== id)), []);

  const push = useCallback((t: ToastInput) => {
    const id = ++seq.current;
    setItems(xs => [...xs, { ...t, id }].slice(-3));
    const ttl = t.tone === 'crit' ? 6500 : t.tone === 'err' ? 6000 : 4200;
    setTimeout(() => setItems(xs => xs.map(x => (x.id === id ? { ...x, leaving: true } : x))), ttl);
    setTimeout(() => remove(id), ttl + 320);
  }, [remove]);

  const value = useMemo(() => push, [push]);

  return (
    <Ctx.Provider value={value}>
      {children}
      <div className="fixed bottom-4 right-4 z-[80] flex flex-col gap-2 w-[360px] max-w-[calc(100vw-32px)]">
        {items.map(t => {
          const [DefIcon, c] = TONES[t.tone ?? 'info'];
          const Ic = t.icon ?? DefIcon;
          return (
            <div key={t.id} className="fade-in rounded-xl border border-gray-200 bg-white shadow-lg px-4 py-3 flex gap-3 transition-opacity duration-300" style={{ opacity: t.leaving ? 0 : 1 }}>
              <div className={`mt-0.5 ${c}`}><Ic className="w-4 h-4" strokeWidth={1.75} /></div>
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium text-gray-900">{t.title}</div>
                {t.body && <div className="text-[13px] text-gray-500 mt-0.5 break-words">{t.body}</div>}
                {t.action && (
                  <button className="mt-2 btn btn-white btn-sm" onClick={() => { t.action!.onClick(); remove(t.id); }}>{t.action.label}</button>
                )}
              </div>
              <button className="text-gray-400 hover:text-gray-700 h-5" onClick={() => remove(t.id)} aria-label="Dismiss"><X className="w-4 h-4" /></button>
            </div>
          );
        })}
      </div>
    </Ctx.Provider>
  );
}

export const useToast = () => useContext(Ctx);
