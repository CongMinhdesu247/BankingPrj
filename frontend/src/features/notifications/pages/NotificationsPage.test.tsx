import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { NotificationsPage } from './NotificationsPage';
import { notificationApi, type Notification } from '../api/notificationApi';
import type { Me } from '../../../shared/types/api';
import { ApiFailure } from '../../../shared/api/httpClient';
let me = { user: { id: 'owner', roles: ['CUSTOMER'] } } as Me;
vi.mock('../../auth/providers/AuthProvider', () => ({ useAuth: () => ({ me }) }));
vi.mock('../api/notificationApi', () => ({ notificationApi: { list: vi.fn(), read: vi.fn() } }));
const item: Notification = { id: 'notice-1', eventId: 'event-1', type: 'TRANSFER_COMPLETED', content: { transactionId: 'transaction-1' }, status: 'UNREAD', createdAt: '2026-10-28T05:00:00Z', readAt: null };
const page = (items: Notification[]) => ({ content: items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0, first: true, last: true });
beforeEach(() => { vi.clearAllMocks(); me = { user: { id: 'owner', roles: ['CUSTOMER'] } } as Me; vi.mocked(notificationApi.list).mockResolvedValue(page([item])); vi.mocked(notificationApi.read).mockResolvedValue(undefined); });
function mount() { return render(<MemoryRouter><NotificationsPage /></MemoryRouter>); }
it('shows the private transaction link and refreshes after marking read', async () => {
  mount(); expect(await screen.findByRole('link', { name: 'Xem chi tiết →' })).toHaveAttribute('href', '/transactions/transaction-1');
  vi.mocked(notificationApi.list).mockResolvedValue(page([{ ...item, status: 'READ' }]));
  await userEvent.click(screen.getByRole('button', { name: 'Đánh dấu đã đọc' }));
  expect(notificationApi.read).toHaveBeenCalledWith('notice-1'); expect(await screen.findByText('Đã đọc')).toBeInTheDocument();
});
it('requests the unread filter and shows an empty inbox', async () => {
  mount(); await screen.findByText('Giao dịch đã hoàn tất'); vi.mocked(notificationApi.list).mockResolvedValue(page([]));
  await userEvent.click(screen.getByLabelText('Chỉ thông báo chưa đọc'));
  expect(await screen.findByText('Chưa có thông báo')).toBeInTheDocument(); expect(notificationApi.list).toHaveBeenLastCalledWith(0, true, expect.any(AbortSignal));
});
it('links a risk notification to its investigation and does not present a score as certainty', async () => {
  me = { user: { id: 'risk', roles: ['RISK_OFFICER'] } } as Me;
  vi.mocked(notificationApi.list).mockResolvedValue(page([{ ...item, type: 'FRAUD_DETECTED', content: { alertId: 'alert-1', riskLevel: 'HIGH', score: 70 } }]));
  mount(); expect(await screen.findByText('Mức rủi ro Cao · 70/100 điểm quy tắc.')).toBeInTheDocument(); expect(screen.getByRole('link', { name: 'Xem chi tiết →' })).toHaveAttribute('href', '/fraud/alerts/alert-1');
});
it('keeps system failures uncertain and reports read errors without hiding the notification', async () => {
  vi.mocked(notificationApi.list).mockResolvedValue(page([{ ...item, type: 'TRANSFER_FAILED', content: { outcome: 'SYSTEM_FAILURE', reasonCode: 'TRANSFER_PROCESSING_ERROR' } }]));
  vi.mocked(notificationApi.read).mockRejectedValue(new ApiFailure('NOTIFICATION_NOT_FOUND', 404));
  mount(); await screen.findByText(/Yêu cầu chưa xác định kết quả/); await userEvent.click(screen.getByRole('button', { name: 'Đánh dấu đã đọc' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy thông báo'); expect(screen.getByText('Chưa đọc')).toBeInTheDocument();
});
it('shows support status, staff preview and a link to the correct private ticket', async () => {
  vi.mocked(notificationApi.list).mockResolvedValue(page([{ ...item, type: 'SUPPORT_STATUS_CHANGED', content: { ticketId: 'ticket-1', subject: 'Kiểm tra giao dịch', ticketStatus: 'IN_PROGRESS', messagePreview: 'Chúng tôi đang đối chiếu thông tin.' } }]));
  mount(); expect(await screen.findByText('Tra soát đã đổi trạng thái')).toBeInTheDocument();
  expect(screen.getByText('Đang xử lý')).toHaveClass('ticket-status-in_progress');
  expect(screen.getByText('Chúng tôi đang đối chiếu thông tin.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Xem chi tiết →' })).toHaveAttribute('href', '/support/ticket-1');
});
it('presents a CSKH message as support correspondence rather than a failed transfer', async () => {
  vi.mocked(notificationApi.list).mockResolvedValue(page([{ ...item, type: 'SUPPORT_REPLY', content: { ticketId: 'ticket-2', subject: 'Tra soát của tôi', messagePreview: 'Bạn vui lòng bổ sung thông tin.' } }]));
  mount(); expect(await screen.findByText('CSKH đã phản hồi tra soát')).toBeInTheDocument();
  expect(screen.getByText('Bạn vui lòng bổ sung thông tin.')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Xem chi tiết →' })).toHaveAttribute('href', '/support/ticket-2');
});
