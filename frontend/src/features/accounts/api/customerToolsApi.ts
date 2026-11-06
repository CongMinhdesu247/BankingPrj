import { request } from '../../../shared/api/httpClient';
import type { Customer, Page } from '../../../shared/types/api';
export interface LoginEvent { success: boolean; createdAt: string }
export interface StatementRow {
  transactionId: string;
  reference: string;
  description: string | null;
  direction: 'CREDIT' | 'DEBIT';
  amount: string;
  balanceAfter: string;
  createdAt: string
}
export interface Statement {
  accountId: string;
  accountNumber: string;
  month: string;
  openingBalance: string;
  incoming: string;
  outgoing: string;
  closingBalance: string;
  entries: Page<StatementRow>
}
export const customerToolsApi = {
  statement: (accountId: string, month: string, page: number, signal?: AbortSignal) => request<Statement>(`/me/statement?${new URLSearchParams({ accountId, month, page: String(page) })}`, { signal }),
  logins: (page: number, signal?: AbortSignal) => request<Page<LoginEvent>>(`/me/login-history?page=${page}`, { signal }),
  contact: (email: string, phone: string) => request<Customer>('/me/contact', { method: 'PATCH', body: { email, phone } }),
};
