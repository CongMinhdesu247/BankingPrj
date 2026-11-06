import { useLayoutEffect, useReducer, useRef, type ChangeEvent, type ClipboardEvent, type InputHTMLAttributes, type KeyboardEvent } from 'react';
interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'type'> {
  value: string;
  onValueChange: (value: string) => void;
}
export function formatVndInput(value: string): string {
  return /^\d+$/.test(value) ? value.replace(/\B(?=(\d{3})+(?!\d))/g, '.') : value;
}
function digitsIn(text: string): number { return (text.match(/\d/g) ?? []).length; }
function dotsIn(text: string): number { return (text.match(/\./g) ?? []).length; }
function caretAfterDigits(text: string, count: number): number {
  if (!count) return 0;
  for (let i = 0, seen = 0; i < text.length; i++) if (/\d/.test(text[i]) && ++seen === count) return i + 1;
  return text.length;
}
export function VndInput({ value, onValueChange, ...props }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const caret = useRef<number | null>(null);
  const [, edited] = useReducer((revision: number) => revision + 1, 0);
  // Restore selection after React has rendered the formatted value, including no-op separator deletion.
  useLayoutEffect(() => {
    const input = inputRef.current;
    if (caret.current !== null && input === document.activeElement) input?.setSelectionRange(caret.current, caret.current);
    caret.current = null;
  });
  function commit(next: string, digits: number, literalPosition: number) {
    caret.current = /^\d*$/.test(next) ? caretAfterDigits(formatVndInput(next), digits) : literalPosition;
    onValueChange(next);
    edited();
  }
  function change(event: ChangeEvent<HTMLInputElement>) {
    const input = event.currentTarget, entered = input.value;
    const position = input.selectionStart ?? entered.length;
    // Some keyboards/IME omit InputEvent.data and inputType. Recognize our existing separators from the value itself.
    const existingSeparators = /^\d*$/.test(value) && /^[\d.]*$/.test(entered)
      && dotsIn(entered) <= dotsIn(formatVndInput(value));
    const grouped = /^\d{1,3}(\.\d{3})+$/.test(entered);
    const next = existingSeparators || grouped ? entered.replace(/\./g, '') : entered;
    commit(next, digitsIn(entered.slice(0, position)), position);
  }
  function paste(event: ClipboardEvent<HTMLInputElement>) {
    event.preventDefault();
    const input = event.currentTarget;
    const pasted = event.clipboardData.getData('text').trim();
    const start = input.selectionStart ?? 0, end = input.selectionEnd ?? start;
    const before = input.value.slice(0, start), after = input.value.slice(end);
    const validPaste = /^\d*$/.test(pasted) || /^\d{1,3}(\.\d{3})+$/.test(pasted);
    // Validate the clipboard independently; a decimal paste must never silently become a different amount.
    const next = validPaste && /^\d*$/.test(value)
      ? (before + pasted + after).replace(/\./g, '')
      : before + pasted + after;
    commit(next, digitsIn(before + pasted), before.length + pasted.length);
  }
  function keyDown(event: KeyboardEvent<HTMLInputElement>) {
    const input = event.currentTarget, position = input.selectionStart ?? 0;
    if (input.selectionEnd !== position || !/^\d+$/.test(value)) return;
    const backward = event.key === 'Backspace' && input.value[position - 1] === '.';
    const forward = event.key === 'Delete' && input.value[position] === '.';
    if (!backward && !forward) return;
    event.preventDefault();
    const before = digitsIn(input.value.slice(0, position));
    const index = backward ? before - 1 : before;
    commit(value.slice(0, index) + value.slice(index + 1), backward ? before - 1 : before, position);
  }
  return <input
    {...props}
    ref={inputRef}
    type="text"
    inputMode="numeric"
    value={formatVndInput(value)}
    onChange={change}
    onPaste={paste}
    onKeyDown={keyDown} />;
}
