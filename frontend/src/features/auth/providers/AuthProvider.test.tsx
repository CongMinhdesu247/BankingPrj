import { act, render, screen } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { authApi } from '../api/authApi';
import { AuthProvider, useAuth } from './AuthProvider';
import { PENDING_KEY, savePending, type PendingTransfer } from '../../transfers/lib/pendingTransfer';
import type { Me, TokenResponse } from '../../../shared/types/api';
vi.mock('../api/authApi', () => ({ authApi: { login: vi.fn(), me: vi.fn() } }));
const owner = '00000000-0000-0000-0000-000000000001';
const me = { user: { id: owner, username: 'test.customer', roles: ['CUSTOMER'] }, customer: null } as unknown as Me;
let current!: ReturnType<typeof useAuth>;
function Probe() {
  current = useAuth();
  return <div>
    {current.me ? 'Authenticated' : 'Signed out'}
    <span>{current.notice}</span>
  </div>;
}
const pending: PendingTransfer = {
  version: 1,
  ownerId: owner,
  key: '00000000-0000-0000-0000-000000000002',
  createdAt: new Date().toISOString(),
  payload: {
    sourceAccountId: owner,
    destinationAccountId: '00000000-0000-0000-0000-000000000003',
    amount: '1000',
    currency: 'VND',
    description: null
  }
};
beforeEach(() => {
  vi.mocked(authApi.login).mockResolvedValue({ accessToken: 'memory-only-test-token', expiresIn: 900, user: me.user } as TokenResponse);
  vi.mocked(authApi.me).mockResolvedValue(me);
});
it('keeps the token out of browser storage', async () => {
  render(<AuthProvider><Probe /></AuthProvider>);
  await act(async () => current.login('test.customer', 'test-password'));
  expect(screen.getByText('Authenticated')).toBeInTheDocument();
  expect(localStorage.length).toBe(0);
  expect(sessionStorage.length).toBe(0);
});
it('expires the session but preserves same-user pending intent for reauthentication', async () => {
  vi.useFakeTimers();
  render(<AuthProvider><Probe /></AuthProvider>);
  await act(async () => current.login('test.customer', 'test-password'));
  savePending(pending);
  act(() => { vi.advanceTimersByTime(900000); });
  expect(screen.getByText('Signed out')).toBeInTheDocument();
  expect(current.notice).toContain('hết hạn');
  expect(sessionStorage.getItem(PENDING_KEY)).not.toBeNull();
});
it('explicit logout clears pending data only after the user confirms the warning', async () => {
  render(<AuthProvider><Probe /></AuthProvider>);
  await act(async () => current.login('test.customer', 'test-password'));
  savePending(pending);
  act(() => current.logout());
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  expect(screen.getByText('Authenticated')).toBeInTheDocument();
  act(() => screen.getByRole('button', { name: 'Vẫn đăng xuất' }).click());
  expect(screen.getByText('Signed out')).toBeInTheDocument();
  expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
});
it('removes the previous user’s pending payload when a different user logs in', async () => {
  savePending({ ...pending, ownerId: '00000000-0000-0000-0000-000000000004' });
  render(<AuthProvider><Probe /></AuthProvider>);
  await act(async () => current.login('test.customer', 'test-password'));
  expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
});
