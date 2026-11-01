import type { Notification } from '../api/notificationApi';
import { ticketStatusLabels } from '../../support/components/TicketStatusBadge';
export function isSupportNotification(item: Notification) { return item.type === 'SUPPORT_STATUS_CHANGED' || item.type === 'SUPPORT_REPLY'; }
export function supportNotificationTitle(item: Notification) { return item.type === 'SUPPORT_REPLY' ? 'CSKH đã phản hồi tra soát' : 'Tra soát đã đổi trạng thái'; }
export function supportNotificationDescription(item: Notification) {
  const subject = item.content.subject || 'Yêu cầu tra soát của bạn';
  return item.type === 'SUPPORT_REPLY' ? `Bạn có tin nhắn mới từ CSKH về “${subject}”.` : `“${subject}” chuyển sang ${ticketStatusLabels[item.content.ticketStatus ?? ''] ?? 'trạng thái mới'}.`;
}
export function supportNotificationTarget(item: Notification) { return item.content.ticketId ? `/support/${encodeURIComponent(item.content.ticketId)}` : '/support'; }
