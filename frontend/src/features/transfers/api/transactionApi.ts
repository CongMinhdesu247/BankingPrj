import { request } from '../../../shared/api/httpClient';
import type { Page, Transaction, TransactionDetail } from '../../../shared/types/api';
export interface HistoryQuery { page?: number; size?: number; accountId?: string; from?: string; to?: string }
export const transactionApi = {
  detail: (id: string, signal?: AbortSignal) => request<TransactionDetail>(`/transactions/${encodeURIComponent(id)}`, { signal }),
  history: (query: HistoryQuery = {}, signal?: AbortSignal) => {
    const params = new URLSearchParams({ page: String(query.page ?? 0), size: String(query.size ?? 10) });
    if (query.from) params.set('from', query.from);
    if (query.to) params.set('to', query.to);
    return request<Page<Transaction>>(`${query.accountId ? `/accounts/${encodeURIComponent(query.accountId)}` : '/me'}/transactions?${params}`, { signal });
  },
};
