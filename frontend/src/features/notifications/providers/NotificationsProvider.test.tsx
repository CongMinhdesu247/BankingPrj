import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { NotificationsProvider, useNotifications } from './NotificationsProvider';
import { notificationApi, type Notification } from '../api/notificationApi';
vi.mock('../../auth/providers/AuthProvider', () => ({ useAuth: () => ({ me: { user: { id: 'customer-1' } } }) }));
vi.mock('../api/notificationApi', () => ({ notificationApi: { summary: vi.fn(), read: vi.fn() } }));
const item: Notification = {
  id: 'support-1',
  eventId: 'event-1',
  type: 'SUPPORT_REPLY',
  content: { ticketId: 'ticket-1', subject: 'Kiểm tra giao dịch', messagePreview: 'CSKH vừa phản hồi.' },
  status: 'UNREAD',
  readAt: null,
  createdAt: '2026-10-31T08:00:00Z'
};
function Probe() {
  const value = useNotifications();
  return <span>Chưa đọc: {value?.unread}</span>;
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  vi.mocked(notificationApi.read).mockResolvedValue(undefined);
});
afterEach(() => {
  vi.clearAllMocks();
  vi.restoreAllMocks();
  vi.useRealTimers();
});
it('does not toast historical items, then alerts on a new staff reply and updates unread count', async () => {
  vi.mocked(notificationApi.summary).mockResolvedValueOnce({ unreadCount: 1, latest: { ...item, id: 'historical' } }).mockResolvedValue({ unreadCount: 2, latest: item });
  const view = render(<MemoryRouter>
    <NotificationsProvider><Probe /></NotificationsProvider>
  </MemoryRouter>);
  await act(async () => { });
  expect(screen.getByText('Chưa đọc: 1')).toBeInTheDocument();
  expect(screen.queryByRole('complementary', { name: 'Thông báo tra soát mới' })).not.toBeInTheDocument();
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  expect(screen.getByText('Chưa đọc: 2')).toBeInTheDocument();
  expect(screen.getByText('CSKH đã phản hồi tra soát')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Mở trao đổi với CSKH' })).toHaveAttribute('href', '/support/ticket-1');
  fireEvent.click(screen.getByRole('button', { name: 'Đóng thông báo tra soát' }));
  await act(async () => { await vi.advanceTimersByTimeAsync(10000); });
  expect(screen.queryByText('CSKH đã phản hồi tra soát')).not.toBeInTheDocument();
  view.unmount();
  const calls = vi.mocked(notificationApi.summary).mock.calls.length;
  await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
  expect(notificationApi.summary).toHaveBeenCalledTimes(calls);
});
it('pauses polling in hidden tabs and refreshes when the user returns', async () => {
  vi.mocked(notificationApi.summary).mockResolvedValue({ unreadCount: 0, latest: null });
  render(<MemoryRouter>
    <NotificationsProvider><Probe /></NotificationsProvider>
  </MemoryRouter>);
  await act(async () => { });
  expect(notificationApi.summary).toHaveBeenCalledTimes(1);
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
  await act(async () => { await vi.advanceTimersByTimeAsync(20000); });
  expect(notificationApi.summary).toHaveBeenCalledTimes(1);
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
  expect(notificationApi.summary).toHaveBeenCalledTimes(2);
});
