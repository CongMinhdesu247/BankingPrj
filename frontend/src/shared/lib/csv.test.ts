import { expect, it } from 'vitest';
import { csvCell } from './csv';
it('neutralizes spreadsheet formulas in customer supplied descriptions', () => {
  for (const value of ['=HYPERLINK("bad")', ' +SUM(1,2)', '-1+2', '@SUM(1)', '\t=CMD()']) {
    expect(csvCell(value)).toMatch(/^"'/);
  }
  expect(csvCell('Gửi bạn "cà phê", hôm nay')).toBe('"Gửi bạn ""cà phê"", hôm nay"');
});
