import { beforeEach, expect, it } from 'vitest';
import { clearMatchingPending, expiredPending, PENDING_KEY, readPending, savePending, type PendingTransfer } from './pendingTransfer';
const owner = '00000000-0000-0000-0000-000000000001';
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
beforeEach(() => sessionStorage.clear());
it('restores exactly one intent for the same user without storing a token', () => {
  expect(savePending(pending)).toBe(true);
  expect(readPending(owner)).toEqual(pending);
  expect(sessionStorage.getItem(PENDING_KEY)).not.toMatch(/token|password/i);
});
it('removes another user’s pending payload at login', () => {
  savePending(pending);
  expect(readPending('00000000-0000-0000-0000-000000000004')).toBeNull();
  expect(sessionStorage.getItem(PENDING_KEY)).toBeNull();
});
it('does not let a late response clear a different user’s newer intent', () => {
  savePending(pending);
  clearMatchingPending('other-user', pending.key);
  clearMatchingPending(owner, 'old-key');
  expect(readPending(owner)).toEqual(pending);
  clearMatchingPending(owner, pending.key);
  expect(readPending(owner)).toBeNull();
});
it('keeps expired keys for history review instead of silently treating them as new transfers', () => {
  const expired = { ...pending, createdAt: new Date(Date.now() - 8 * 86400000).toISOString() };
  savePending(expired);
  expect(expiredPending(readPending(owner)!)).toBe(true);
});
it('rejects malformed persisted payloads', () => {
  sessionStorage.setItem(PENDING_KEY, '{broken');
  expect(readPending(owner)).toBeNull();
  sessionStorage.setItem(PENDING_KEY, JSON.stringify({ ...pending, payload: { ...pending.payload, amount: '1e3' } }));
  expect(readPending(owner)).toBeNull();
});
