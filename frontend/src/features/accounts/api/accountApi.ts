import { request } from '../../../shared/api/httpClient';
import type { Account, Page, Recipient, TransferLimit } from '../../../shared/types/api';
export const accountApi = {
  get: (id: string, signal?: AbortSignal) => request<Account>(`/accounts/${encodeURIComponent(id)}`, { signal }),
  list: (page = 0, signal?: AbortSignal) => request<Page<Account>>(`/me/accounts?page=${page}&size=100`, { signal }),
  async all(signal?: AbortSignal): Promise<Account[]> {
    const accounts: Account[] = [];
    for (let page = 0; page < 100; page++) {
      const response = await this.list(page, signal);
      accounts.push(...response.content);
      if (response.last) return accounts;
    }
    throw new Error('Account list is too large');
  },
  limits: (id: string, signal?: AbortSignal) => request<TransferLimit>(`/me/transfer-limits?accountId=${encodeURIComponent(id)}`, { signal }),
  recipient: (accountNumber: string, signal?: AbortSignal) => request<Recipient>(`/transfer-recipients?accountNumber=${encodeURIComponent(accountNumber)}`, { signal }),
  recipientById: (id: string, signal?: AbortSignal) => request<Recipient>(`/transfer-recipients/${encodeURIComponent(id)}`, { signal }),
};
