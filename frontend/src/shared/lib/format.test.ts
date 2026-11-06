import { describe, expect, it } from 'vitest';
import { money, validAmount, vietnamDayStart, vietnamNextDayStart, wholeVnd } from './format';
describe('exact VND and Vietnamese days', () => {
  it('formats the maximum supported VND without Number precision loss', () => {
    expect(money('99999999999999999.00')).toBe('99.999.999.999.999.999 ₫');
    expect(wholeVnd('99999999999999999.00') + 1n).toBe(100000000000000000n);
  });
  it.each(['', '0', '-1', '1.5', '1,000', '1.000', ' 100', '1e3', '100000000000000000'])('rejects ambiguous or invalid transfer amount %j', value => { expect(validAmount(value)).toBe(false); });
  it('accepts only whole positive transfer strings and rejects fractional balances', () => {
    expect(validAmount('1000')).toBe(true);
    expect(validAmount('99999999999999999')).toBe(true);
    expect(() => wholeVnd('1.01')).toThrow();
  });
  it('turns the inclusive UI date range into half-open Vietnam-day instants', () => {
    expect(vietnamDayStart('2026-10-27')).toBe('2026-10-26T17:00:00.000Z');
    expect(vietnamNextDayStart('2026-10-27')).toBe('2026-10-27T17:00:00.000Z');
  });
});
