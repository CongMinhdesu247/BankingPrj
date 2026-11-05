import { request } from '../../../shared/api/httpClient';
import type { Me, TokenResponse } from '../../../shared/types/api';
export interface RegisterInput { fullName: string; email: string; phone: string | null; username: string; password: string }
export interface RegisterResult { username: string; accountNumber: string }
export const authApi = {
  register: (body: RegisterInput) => request<RegisterResult>('/auth/register', { method: 'POST', auth: false, body, timeoutMs: 30000 }),
  login: (username: string, password: string, otp?: string) => request<TokenResponse>('/auth/login', { method: 'POST', auth: false, body: { username, password, ...(otp ? { otp } : {}) } }),
  me: (signal?: AbortSignal) => request<Me>('/me', { signal }),
};
