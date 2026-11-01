import type { TransferPayload } from '../../../shared/types/api';
export const PENDING_KEY = 'banking.pendingTransfer.v1';
export interface PendingTransfer { version: 1; ownerId: string; key: string; payload: TransferPayload; createdAt: string }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export function readPending(ownerId: string): PendingTransfer | null {
  try {
    const raw = sessionStorage.getItem(PENDING_KEY);
    if (!raw) return null;
    const p = JSON.parse(raw) as PendingTransfer;
    if (p.version !== 1 || p.ownerId !== ownerId || !uuid.test(p.ownerId) || !uuid.test(p.key)
      || !uuid.test(p.payload.sourceAccountId) || !uuid.test(p.payload.destinationAccountId)
      || !/^[0-9]{1,17}$/.test(p.payload.amount) || BigInt(p.payload.amount) <= 0n || p.payload.currency !== 'VND'
      || !(p.payload.description === null || (typeof p.payload.description === 'string' && p.payload.description.length <= 255))
      || !Number.isFinite(Date.parse(p.createdAt))) { clearPending(); return null; }
    return p;
  } catch { clearPending(); return null; }
}
export function savePending(pending: PendingTransfer): boolean {
  try { sessionStorage.setItem(PENDING_KEY, JSON.stringify(pending)); return true; }
  catch { return false; }
}
export function clearPending() { try { sessionStorage.removeItem(PENDING_KEY); } catch { /* Restricted storage. */ } }
export function clearMatchingPending(ownerId: string, key: string) {
  try { const raw = sessionStorage.getItem(PENDING_KEY); if (!raw) return; const p = JSON.parse(raw); if (p.ownerId === ownerId && p.key === key) clearPending(); } catch { /* Do not clear another user's newer intent. */ }
}
export function expiredPending(pending: PendingTransfer): boolean { return Date.now() - Date.parse(pending.createdAt) >= 7 * 86400000; }
