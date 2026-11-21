/** Text input with a fixed-position suggestion list (keyboard: ↑ ↓ Enter Tab Esc), as in the prototype. */
import { useEffect, useLayoutEffect, useRef, useState, type InputHTMLAttributes } from 'react';
import { createPortal } from 'react-dom';

export interface AcItem { value: string; meta?: string; hint?: string }

interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'> {
  value: string;
  onChange: (v: string) => void;
  /** Suggestions for the current text; may be async. */
  suggest: (q: string) => AcItem[] | Promise<AcItem[]>;
  header?: string;
  /** Called when an item is picked; default replaces the value. */
  onPick?: (item: AcItem) => void;
}

export function Autocomplete({ value, onChange, suggest, header, onPick, className, onKeyDown, ...rest }: Props) {
  const ref = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [items, setItems] = useState<AcItem[]>([]);
  const [idx, setIdx] = useState(0);
  const [pos, setPos] = useState<{ left: number; top: number; width: number } | null>(null);
  const reqId = useRef(0);

  const refresh = async (q: string) => {
    const id = ++reqId.current;
    const r = await suggest(q);
    if (id !== reqId.current) return;
    const exact = r.length === 1 && r[0].value.toLowerCase() === q.trim().toLowerCase();
    setItems(exact ? [] : r.slice(0, 10));
    setIdx(0);
  };

  useLayoutEffect(() => {
    if (!open || !ref.current) return;
    const r = ref.current.getBoundingClientRect();
    setPos({ left: r.left, top: r.bottom + 4, width: Math.max(r.width, 300) });
  }, [open, items]);

  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', close, true);
    return () => { window.removeEventListener('resize', close); window.removeEventListener('scroll', close, true); };
  }, [open]);

  const choose = (it: AcItem) => {
    if (onPick) onPick(it); else onChange(it.value);
    setOpen(false);
    ref.current?.focus();
  };

  const q = value.split(',').pop()!.trim().toLowerCase();
  const hl = (s: string) => {
    const i = q ? s.toLowerCase().indexOf(q) : -1;
    if (i < 0) return s;
    return <>{s.slice(0, i)}<b className="text-gray-900">{s.slice(i, i + q.length)}</b>{s.slice(i + q.length)}</>;
  };

  return (
    <>
      <input
        ref={ref}
        {...rest}
        className={className}
        value={value}
        autoComplete="off"
        spellCheck={false}
        onFocus={() => { setOpen(true); refresh(value); }}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onChange={e => { onChange(e.target.value); setOpen(true); refresh(e.target.value); }}
        onKeyDown={e => {
          if (open && items.length) {
            if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
              e.preventDefault();
              setIdx(i => (i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length);
              return;
            }
            if (e.key === 'Enter' || (e.key === 'Tab' && value)) { e.preventDefault(); choose(items[idx]); return; }
            if (e.key === 'Escape') { e.stopPropagation(); setOpen(false); return; }
          }
          onKeyDown?.(e);
        }}
      />
      {open && items.length > 0 && pos && createPortal(
        <div
          className="fixed z-[95] rounded-xl border border-gray-200 bg-white shadow-xl p-1 max-h-72 overflow-auto"
          style={{ left: pos.left, top: pos.top, width: pos.width }}
          onMouseDown={e => e.preventDefault()}
        >
          {header && <div className="px-2.5 pt-1.5 pb-1 text-[11px] font-medium text-gray-400">{header}</div>}
          {items.map((it, i) => (
            <div key={it.value + i} className={`ac-item ${i === idx ? 'sel' : ''} rounded-lg px-2.5 py-1.5 cursor-pointer`} onMouseEnter={() => setIdx(i)} onClick={() => choose(it)}>
              <div className="flex items-center gap-2">
                <span className="font-mono text-[12.5px] text-gray-700 truncate">{hl(it.value)}</span>
                {it.meta && <span className="ml-auto shrink-0 text-[11px] text-gray-400">{it.meta}</span>}
              </div>
              {it.hint && <div className="text-[11px] text-gray-400 truncate">{it.hint}</div>}
            </div>
          ))}
        </div>,
        document.body,
      )}
    </>
  );
}
