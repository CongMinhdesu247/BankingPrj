import { request } from '../../../shared/api/httpClient';
import type { Beneficiary, Page } from '../../../shared/types/api';
export const beneficiaryApi = {
  get: (id: string, signal?: AbortSignal) => request<Beneficiary>(`/me/beneficiaries/${encodeURIComponent(id)}`, { signal }),
  list: (page = 0, signal?: AbortSignal) => request<Page<Beneficiary>>(`/me/beneficiaries?page=${page}&size=20`, { signal }),
  create: (destinationAccountId: string, nickname: string) => request<Beneficiary>('/me/beneficiaries', { method: 'POST', body: { destinationAccountId, nickname: nickname.trim() || null } }),
  update: (id: string, nickname: string) => request<Beneficiary>(`/me/beneficiaries/${encodeURIComponent(id)}`, { method: 'PATCH', body: { nickname: nickname.trim() || null } }),
  remove: (id: string) => request<void>(`/me/beneficiaries/${encodeURIComponent(id)}`, { method: 'DELETE' }),
};
