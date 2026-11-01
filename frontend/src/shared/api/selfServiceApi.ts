import { request } from './httpClient';
import type { Page, TransferLimit } from '../types/api';
export interface Session { id: string; deviceLabel: string; createdAt: string; expiresAt: string; current: boolean }
export interface Template { id: string; name: string; sourceAccountId: string; destinationAccountId: string; destinationAccountNumber: string; amount: string; description: string | null }
export interface Ticket { id: string; transactionId: string; reference: string; username: string; subject: string; category: string; status: string; assignedUsername: string | null; version: number; createdAt: string; updatedAt: string }
export interface TicketDetail { ticket: Ticket; messages: { id: string; author: string; body: string; createdAt: string }[] }
export interface LimitRequest { id: string; accountId: string; accountNumber: string; username: string; perTransferAmount: string; dailyAmount: string; reason: string; status: string; reviewNote: string | null; createdAt: string }
export interface LimitSettings { effective: TransferLimit; ceiling: { perTransferAmount: string; dailyAmount: string } }
export interface LimitChange { accountId: string; perTransferAmount: string; dailyAmount: string; reason: string }
export const selfServiceApi = {
  recoveryOptions: (signal?: AbortSignal) => request<{ deliveryMode: 'LOCAL_MAILBOX' | 'UNAVAILABLE'; localMailboxPath: string | null }>('/auth/recovery-options', { signal, auth: false }),
  security: (signal?: AbortSignal) => request<{ totpEnabled: boolean; recoveryCodesRemaining: number }>('/me/security', { signal }),
  sessions: (signal?: AbortSignal) => request<Session[]>('/me/security/sessions', { signal }),
  revoke: (id?: string) => request<void>(`/me/security/sessions${id ? `/${id}` : ''}`, { method: 'DELETE' }),
  password: (password: string, newPassword: string, otp: string) => request<void>('/me/security/password', { method: 'POST', body: { password, newPassword, otp } }),
  setup: (password: string, otp: string) => request<{ secret: string; uri: string }>('/me/security/totp/setup', { method: 'POST', body: { password, otp } }),
  enable: (code: string) => request<{ recoveryCodes: string[] }>('/me/security/totp/enable', { method: 'POST', body: { code } }),
  disable: (password: string, otp: string) => request<void>('/me/security/totp/disable', { method: 'POST', body: { password, otp } }),
  forgot: (username: string) => request<{ message: string }>('/auth/forgot-password', { method: 'POST', auth: false, body: { username } }),
  reset: (token: string, newPassword: string, otp: string) => request<void>('/auth/reset-password', { method: 'POST', auth: false, body: { token, newPassword, otp } }),
  templates: (page: number, signal?: AbortSignal) => request<Page<Template>>(`/me/transfer-templates?page=${page}`, { signal }),
  template: (id: string, signal?: AbortSignal) => request<Template>(`/me/transfer-templates/${encodeURIComponent(id)}`, { signal }),
  saveTemplate: (body: { name: string; sourceAccountId: string; destinationAccountId: string; amount: string; description: string | null }) => request<Template>('/me/transfer-templates', { method: 'POST', body }),
  deleteTemplate: (id: string) => request<void>(`/me/transfer-templates/${id}`, { method: 'DELETE' }),
  tickets: (staff: boolean, page: number, status: string, signal?: AbortSignal) => request<Page<Ticket>>(`/${staff ? 'staff' : 'me'}/support-tickets?${new URLSearchParams({ page: String(page), status })}`, { signal }),
  ticket: (staff: boolean, id: string, signal?: AbortSignal) => request<TicketDetail>(`/${staff ? 'staff' : 'me'}/support-tickets/${id}`, { signal }),
  createTicket: (body: { transactionId: string; subject: string; category: string; message: string }) => request<TicketDetail>('/me/support-tickets', { method: 'POST', body }),
  reply: (staff: boolean, id: string, message: string) => request<TicketDetail>(`/${staff ? 'staff' : 'me'}/support-tickets/${id}/messages`, { method: 'POST', body: { message } }),
  updateTicket: (staff: boolean, id: string, expectedVersion: number, status: string, message: string) => request<TicketDetail>(`/${staff ? 'staff' : 'me'}/support-tickets/${id}`, { method: 'PATCH', body: { expectedVersion, status, message } }),
  limitSettings: (accountId: string, signal?: AbortSignal) => request<LimitSettings>(`/me/limit-settings?accountId=${accountId}`, { signal }),
  lowerLimit: (body: LimitChange) => request<TransferLimit>('/me/personal-transfer-limits', { method: 'PUT', body }),
  requestLimit: (body: LimitChange) => request<LimitRequest>('/me/limit-requests', { method: 'POST', body }),
  limitRequests: (admin: boolean, page: number, signal?: AbortSignal) => request<Page<LimitRequest>>(`/${admin ? 'staff' : 'me'}/limit-requests?page=${page}`, { signal }),
  reviewLimit: (id: string, approve: boolean, note: string) => request<LimitRequest>(`/staff/limit-requests/${id}`, { method: 'PATCH', body: { approve, note } }),
};
