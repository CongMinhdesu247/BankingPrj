import { request } from '../../../shared/api/httpClient';
import type { Page } from '../../../shared/types/api';
export interface Notification { id: string; eventId: string; type: 'TRANSFER_COMPLETED' | 'TRANSFER_FAILED' | 'FRAUD_DETECTED' | 'SUPPORT_STATUS_CHANGED' | 'SUPPORT_REPLY'; content: { transactionId?: string; alertId?: string; attemptId?: string; reasonCode?: string; outcome?: string; riskLevel?: string; score?: number; ticketId?: string; subject?: string; ticketStatus?: string; previousStatus?: string; sender?: string; messagePreview?: string }; status: 'UNREAD' | 'READ'; readAt: string | null; createdAt: string }
export interface NotificationSummary { unreadCount: number; latest: Notification | null }
export const notificationApi = {
  summary: (signal?: AbortSignal) => request<NotificationSummary>('/me/notifications/summary', { signal }),
  list: (page = 0, unreadOnly = false, signal?: AbortSignal) => request<Page<Notification>>(`/me/notifications?page=${page}&size=20&unreadOnly=${unreadOnly}`, { signal }),
  read: (id: string) => request<void>(`/me/notifications/${encodeURIComponent(id)}/read`, { method: 'PATCH' }),
};
