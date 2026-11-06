import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { accountApi } from '../../accounts/api/accountApi';
import { beneficiaryApi } from '../../beneficiaries/api/beneficiaryApi';
import { transferApi } from '../api/transferApi';
import { ApiFailure } from '../../../shared/api/httpClient';
import { PENDING_KEY, savePending, type PendingTransfer } from '../lib/pendingTransfer';
import { TransferPage } from './TransferPage';
import type { Account, Me, TransactionDetail } from '../../../shared/types/api';
const owner = '00000000-0000-0000-0000-000000000001',
  sourceId = '00000000-0000-0000-0000-000000000002',
  destinationId = '00000000-0000-0000-0000-000000000003';
const accounts: Account[] = [{
  id: sourceId,
  customerId: owner,
  accountNumber: '1000000000001',
  currency: 'VND',
  balance: '1000000.00',
  status: 'ACTIVE',
  createdAt: '',
  updatedAt: ''
}];
const me = { user: { id: owner, roles: ['CUSTOMER'] } } as Me;
vi.mock('../../auth/providers/AuthProvider', () => ({ useAuth: () => ({ me }) }));
vi.mock('../../accounts/providers/AccountsProvider', () => ({ useAccounts: () => ({ accounts, loading: false, error: null, refresh: vi.fn() }) }));
vi.mock('../../accounts/api/accountApi', () => ({ accountApi: { limits: vi.fn(), recipient: vi.fn(), recipientById: vi.fn() } }));
vi.mock('../../beneficiaries/api/beneficiaryApi', () => ({ beneficiaryApi: { list: vi.fn(), get: vi.fn() } }));
vi.mock('../api/transferApi', () => ({ transferApi: { send: vi.fn() } }));
const result = { transaction: { id: destinationId }, entries: [] } as unknown as TransactionDetail;
beforeEach(() => {
  vi.mocked(accountApi.limits).mockResolvedValue({
    accountId: sourceId,
    perTransferAmount: '1000000.00',
    dailyAmount: '5000000.00',
    dailyRemaining: '5000000.00',
    dailyUsed: '0.00',
    currency: 'VND',
    date: '2026-10-27',
    timezone: 'Asia/Ho_Chi_Minh',
    defaultApplied: true
  });
  const recipient = {
    id: destinationId,
    accountNumber: '1000000000002',
    currency: 'VND' as const,
    status: 'ACTIVE' as const
  };
  vi.mocked(accountApi.recipient).mockResolvedValue(recipient);
  vi.mocked(accountApi.recipientById).mockResolvedValue(recipient);
  vi.mocked(beneficiaryApi.list).mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true });
  vi.mocked(transferApi.send).mockResolvedValue(result);
});
function mount() {
  return render(<MemoryRouter initialEntries={['/transfer']}>
    <Routes>
      <Route path="/transfer" element={<TransferPage />} />
      <Route path="/transactions/:id" element={<div>Chứng từ đã ghi nhận</div>} />
    </Routes>
  </MemoryRouter>);
}
async function prepare(amount = '1000') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Đến tài khoản'), '1000000000002');
  await user.click(screen.getByRole('button', { name: /^Kiểm tra$/ }));
  await screen.findByText('Tài khoản trong hệ thống');
  if (amount) await user.type(screen.getByLabelText('Số tiền', { exact: true }), amount);
  await user.click(screen.getByRole('button', { name: /^Tiếp tục$/ }));
  return user;
}
it.each(['', '0', '1.5', '100,000', '100000000000000000'])('rejects invalid transfer amount %j before confirmation', async value => {
  mount();
  await prepare(value);
  expect(screen.getByRole('alert')).toHaveTextContent('Số tiền phải là VND nguyên');
  expect(transferApi.send).not.toHaveBeenCalled();
});
it('disables duplicate confirmation and refreshes accounts after success', async () => {
  let resolve!: (value: TransactionDetail) => void;
  vi.mocked(transferApi.send).mockImplementation(() => new Promise(done => { resolve = done; }));
  mount();
  const user = await prepare();
  await user.dblClick(screen.getByRole('button', { name: 'Xác nhận chuyển tiền' }));
  expect(transferApi.send).toHaveBeenCalledOnce();
  expect(sessionStorage.getItem(PENDING_KEY)).toContain('"amount":"1000"');
  await act(async () => resolve(result));
  await screen.findByText('Chứng từ đã ghi nhận');
  expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
});
it('retries a lost response using exactly the original key and frozen payload', async () => {
  vi.mocked(transferApi.send).mockRejectedValueOnce(new ApiFailure('NETWORK_ERROR')).mockResolvedValueOnce(result);
  mount();
  const user = await prepare();
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển tiền' }));
  await screen.findByText('Chưa xác định kết quả giao dịch');
  expect(screen.queryByLabelText('Số tiền', { exact: true })).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: /Thử lại cùng yêu cầu/ }));
  await screen.findByText('Chứng từ đã ghi nhận');
  expect(transferApi.send).toHaveBeenCalledTimes(2);
  expect(vi.mocked(transferApi.send).mock.calls[1]).toEqual(vi.mocked(transferApi.send).mock.calls[0]);
});
it.each([[409, 'INSUFFICIENT_FUNDS', false], [403, 'FORBIDDEN', false], [429, 'RATE_LIMITED', true], [503, 'THROTTLE_UNAVAILABLE', true], [500, 'INTERNAL_ERROR', true]])('handles HTTP %s without losing an uncertain request', async (status, code, retained) => {
  vi.mocked(transferApi.send).mockRejectedValueOnce(new ApiFailure(code, status));
  mount();
  const user = await prepare();
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển tiền' }));
  await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
  expect(sessionStorage.getItem(PENDING_KEY) !== null).toBe(retained);
  expect(transferApi.send).toHaveBeenCalledOnce();
});
const pending = (): PendingTransfer => ({
  version: 1,
  ownerId: owner,
  key: crypto.randomUUID(),
  createdAt: new Date().toISOString(),
  payload: {
    sourceAccountId: sourceId,
    destinationAccountId: destinationId,
    amount: '1234',
    currency: 'VND',
    description: 'Saved intent'
  }
});
it('restores pending intent after reload and cannot silently generate a new key', async () => {
  const record = pending();
  savePending(record);
  mount();
  expect(await screen.findByText('Chưa xác định kết quả giao dịch')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /Thử lại cùng yêu cầu/ }));
  await screen.findByText('Chứng từ đã ghi nhận');
  expect(transferApi.send).toHaveBeenCalledWith(record.payload, record.key);
});
it('blocks retries after seven days until history is reviewed', async () => {
  savePending({ ...pending(), createdAt: new Date(Date.now() - 8 * 86400000).toISOString() });
  mount();
  expect(await screen.findByText('Cần đối chiếu lịch sử giao dịch')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Thử lại cùng yêu cầu/ })).not.toBeInTheDocument();
  expect(transferApi.send).not.toHaveBeenCalled();
});
it('does not let a late success clear a different session’s newer intent', async () => {
  let resolve!: (value: TransactionDetail) => void;
  vi.mocked(transferApi.send).mockImplementation(() => new Promise(done => { resolve = done; }));
  const view = mount();
  const user = await prepare();
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển tiền' }));
  view.unmount();
  const other = { ...pending(), ownerId: '00000000-0000-0000-0000-000000000004' };
  savePending(other);
  await act(async () => resolve(result));
  expect(JSON.parse(sessionStorage.getItem(PENDING_KEY)!)).toEqual(other);
});

it('formats thousands in the input while sending the exact unformatted amount', async () => {
  mount();
  const user = await prepare('500000');
  expect(screen.getByLabelText('Số tiền', { exact: true })).toHaveValue('500.000');
  await user.click(screen.getByRole('button', { name: 'Xác nhận chuyển tiền' }));
  await screen.findByText('Chứng từ đã ghi nhận');
  expect(transferApi.send).toHaveBeenCalledWith(expect.objectContaining({ amount: '500000' }), expect.any(String));
});
