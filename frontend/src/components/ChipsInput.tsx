/** Chips of field paths (group-by) with path autocomplete; Enter or comma adds, Backspace removes the last. */
import { useState } from 'react';
import { X } from 'lucide-react';
import { Autocomplete } from './Autocomplete';
import { useFieldSuggest } from '../hooks/useFieldSuggest';

export function ChipsInput({ values, onChange, strict, placeholder = 'e.g. instrument' }: { values: string[]; onChange: (v: string[]) => void; strict?: boolean; placeholder?: string }) {
  const [text, setText] = useState('');
  const fs = useFieldSuggest(strict);
  const add = (v: string) => {
    v = v.trim().replace(/,$/, '');
    if (v && !values.includes(v)) onChange([...values, v]);
    setText('');
  };
  return (
    <div className="flex flex-wrap items-center gap-1.5 min-h-9 rounded-lg border border-gray-300 bg-white px-1.5 py-1 focus-within:border-gray-500 focus-within:ring-4 focus-within:ring-gray-900/5">
      {values.map((v, i) => (
        <span key={v} className="chip font-mono">{v}
          <button type="button" className="text-gray-400 hover:text-gray-900" onClick={() => onChange(values.filter((_, j) => j !== i))} aria-label={`Remove ${v}`}><X className="w-3 h-3" /></button>
        </span>
      ))}
      <Autocomplete
        className="flex-1 min-w-[140px] bg-transparent outline-none text-sm font-mono text-gray-900 placeholder:text-gray-400 h-6 px-1"
        value={text}
        onChange={setText}
        suggest={q => fs.suggestPaths(q).filter(i => !values.includes(i.value))}
        header="Fields seen in recent events"
        onPick={it => add(it.value)}
        placeholder={values.length ? 'add field…' : placeholder}
        onKeyDown={e => {
          if ((e.key === 'Enter' || e.key === ',') && text.trim()) { e.preventDefault(); add(text); }
          else if (e.key === 'Backspace' && !text && values.length) onChange(values.slice(0, -1));
        }}
      />
    </div>
  );
}
