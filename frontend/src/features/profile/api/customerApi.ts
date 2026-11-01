import { request } from '../../../shared/api/httpClient';
import type { Account, Customer, Page } from '../../../shared/types/api';
export const customerApi = {
  search: (search: string, page = 0, signal?: AbortSignal) => request<Page<Customer>>(`/staff/customers?${new URLSearchParams({ search, page: String(page), size: '20' })}`, { signal }),
  get: (id: string, signal?: AbortSignal) => request<Customer>(`/staff/customers/${encodeURIComponent(id)}`, { signal }),
  accounts: (id: string, page = 0, signal?: AbortSignal) => request<Page<Account>>(`/staff/customers/${encodeURIComponent(id)}/accounts?page=${page}&size=20`, { signal }),
};
