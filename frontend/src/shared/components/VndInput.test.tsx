import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it } from 'vitest';
import { VndInput } from './VndInput';
function Harness({ initial = '' }: { initial?: string }) {
  const [value, setValue] = useState(initial);
  return <><VndInput aria-label="Số tiền" value={value} onValueChange={setValue} /><output data-testid="raw">{value}</output></>;
}
it('adds Vietnamese grouping without losing precision on 17-digit amounts', async () => {
  render(<Harness />); await userEvent.type(screen.getByRole('textbox'), '99999999999999999');
  expect(screen.getByRole('textbox')).toHaveValue('99.999.999.999.999.999'); expect(screen.getByTestId('raw')).toHaveTextContent('99999999999999999');
});
it('accepts a pasted grouped amount and keeps invalid decimal input invalid', async () => {
  render(<Harness />); const input = screen.getByRole('textbox'); const user = userEvent.setup(); await user.click(input); await user.paste('1.500.000');
  expect(input).toHaveValue('1.500.000'); expect(screen.getByTestId('raw')).toHaveTextContent('1500000');
  await user.clear(input); await user.paste('1.5'); expect(screen.getByTestId('raw')).toHaveTextContent('1.5');
});
it('edits digits in the middle and deletes beside a generated separator without getting stuck', async () => {
  render(<Harness initial="500000" />); const input = screen.getByRole('textbox') as HTMLInputElement; const user = userEvent.setup(); await user.click(input);
  input.setSelectionRange(1, 1); await user.keyboard('2'); expect(input).toHaveValue('5.200.000'); expect(input.selectionStart).toBe(3);
  input.setSelectionRange(2, 2); await user.keyboard('{Backspace}'); expect(input).toHaveValue('200.000'); expect(screen.getByTestId('raw')).toHaveTextContent('200000');
  input.setSelectionRange(3, 3); await user.keyboard('{Delete}'); expect(input).toHaveValue('20.000');
  await user.clear(input); expect(input).toHaveValue(''); expect(screen.getByTestId('raw')).toBeEmptyDOMElement();
});

it.each(['insertText', 'insertCompositionText', 'insertReplacementText', undefined])('keeps 5000000 correct when the keyboard omits input data (%s)', inputType => {
  render(<Harness />); const input = screen.getByRole('textbox') as HTMLInputElement;
  for (const digit of '5000000') fireEvent.input(input, { target: { value: input.value + digit }, inputType, data: null });
  expect(input).toHaveValue('5.000.000'); expect(screen.getByTestId('raw')).toHaveTextContent('5000000');
});
