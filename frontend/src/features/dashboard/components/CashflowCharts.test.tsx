import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { CashflowCharts } from './CashflowCharts';
import { cashflowApi, type Cashflow } from '../api/cashflowApi';
import { ApiFailure } from '../../../shared/api/httpClient';
vi.mock('../api/cashflowApi', () => ({ cashflowApi: { get: vi.fn() } }));
function result(days = 30, empty = false): Cashflow {
  const series = Array.from({ length: days }, (_, index) => ({
    date: new Date(Date.UTC(2026, 10, 5 - days + 1 + index)).toISOString().slice(0, 10),
    incoming: !empty && index === days - 1 ? '2000000' : '0',
    outgoing: !empty && index === days - 1 ? '500000' : '0'
  }));
  return {
    from: series[0].date,
    to: series[days - 1].date,
    timezone: 'Asia/Ho_Chi_Minh',
    asOf: '2026-11-05T07:00:00Z',
    incoming: empty ? '0' : '2000000',
    outgoing: empty ? '0' : '500000',
    net: empty ? '0' : '1500000',
    series
  };
}
beforeEach(() => {
  vi.mocked(cashflowApi.get).mockReset();
  vi.mocked(cashflowApi.get).mockImplementation(async days => result(days));
});
it('shows real totals, a ring and keyboard-accessible day details', async () => {
  const { container } = render(<CashflowCharts hidden={false} refreshKey={0} />);
  const chart = await screen.findByRole('img', { name: /Biểu đồ tiền vào/ });
  expect(screen.getAllByText('2.000.000 ₫').length).toBeGreaterThan(0);
  expect(screen.getByText('+1.500.000 ₫')).toBeInTheDocument();
  expect(screen.getByRole('img', { name: /tiền vào 80%, tiền ra 20%/ })).toBeInTheDocument();
  chart.focus();
  fireEvent.keyDown(chart, { key: 'ArrowLeft' });
  expect(container.querySelector('.cashflow-day-detail')).toHaveTextContent('04/11');
});
it('switches to the complete seven-day summary from the API', async () => {
  render(<CashflowCharts hidden={false} refreshKey={0} />);
  await screen.findByRole('img', { name: /Biểu đồ tiền vào/ });
  await userEvent.click(screen.getByRole('button', { name: '7 ngày' }));
  await waitFor(() => expect(cashflowApi.get).toHaveBeenLastCalledWith(7, expect.any(AbortSignal)));
  expect(screen.getByRole('button', { name: '7 ngày' })).toHaveAttribute('aria-pressed', 'true');
});
it('does not fabricate activity for an empty customer', async () => {
  vi.mocked(cashflowApi.get).mockResolvedValue(result(30, true));
  render(<CashflowCharts hidden={false} refreshKey={0} />);
  expect(await screen.findByText('Chưa có dòng tiền trong kỳ')).toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});
it('hides amounts and avoids requesting cashflow when balances are hidden', async () => {
  render(<CashflowCharts hidden refreshKey={0} />);
  expect(screen.getByText('Biểu đồ dòng tiền đang được ẩn cùng số dư.')).toBeInTheDocument();
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  await waitFor(() => expect(cashflowApi.get).not.toHaveBeenCalled());
});
it('shows fetch errors and can retry', async () => {
  vi.mocked(cashflowApi.get).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR'));
  render(<CashflowCharts hidden={false} refreshKey={0} />);
  expect(await screen.findByRole('alert')).toHaveTextContent('Không kết nối');
  await userEvent.click(screen.getByRole('button', { name: /Thử lại/ }));
  expect(await screen.findByRole('img', { name: /Biểu đồ tiền vào/ })).toBeInTheDocument();
});
