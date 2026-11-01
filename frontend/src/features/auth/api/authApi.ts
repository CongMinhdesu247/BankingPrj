import { request } from '../../../shared/api/httpClient';
import type { Me, TokenResponse } from '../../../shared/types/api';
export const authApi = {
  login: (username: string, password: string, otp?: string) => request<TokenResponse>('/auth/login', { method: 'POST', auth: false, body: { username, password, ...(otp ? { otp } : {}) } }),
  me: (signal?: AbortSignal) => request<Me>('/me', { signal }),
};
