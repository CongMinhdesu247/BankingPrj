import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { PasswordRecoveryPage } from './PasswordRecoveryPage';
import { selfServiceApi as api } from '../../../shared/api/selfServiceApi';
import { TicketStatusBadge } from '../../support/components/TicketStatusBadge';
vi.mock('../../../shared/api/selfServiceApi', () => ({ selfServiceApi: { recoveryOptions: vi.fn(), forgot: vi.fn(), reset: vi.fn() } }));
beforeEach(() => {
  vi.clearAllMocks(); window.history.replaceState(null, '', '/');
  vi.mocked(api.recoveryOptions).mockResolvedValue({ deliveryMode: 'LOCAL_MAILBOX', localMailboxPath: 'C:\\banking\\backend\\target\\local-mailbox' });
  vi.mocked(api.forgot).mockResolvedValue({ message: 'Generic response' });
});
it('explains local recovery delivery before submission and never asks for an OTP on the forgot form', async () => {
  render(<MemoryRouter initialEntries={['/forgot-password']}><PasswordRecoveryPage /></MemoryRouter>);
  expect(await screen.findByText('Bản thử nghiệm trên máy này')).toBeInTheDocument();
  expect(screen.getByText(/Chưa gửi email hoặc SMS thật/)).toBeInTheDocument();
  expect(screen.getByText(/C:\\banking\\backend\\target\\local-mailbox/)).toBeInTheDocument();
  expect(screen.queryByLabelText('Mã OTP từ ứng dụng')).not.toBeInTheDocument();
  await userEvent.type(screen.getByLabelText('Tên đăng nhập'), 'mock.an');
  await userEvent.click(screen.getByRole('button', { name: 'Tạo liên kết khôi phục' }));
  expect(await screen.findByRole('status')).toHaveTextContent('Nếu tên đăng nhập hợp lệ');
  expect(api.forgot).toHaveBeenCalledWith('mock.an');
});
it('does not offer to send a recovery link when no delivery channel is configured', async () => {
  vi.mocked(api.recoveryOptions).mockResolvedValue({ deliveryMode: 'UNAVAILABLE', localMailboxPath: null });
  render(<MemoryRouter initialEntries={['/forgot-password']}><PasswordRecoveryPage /></MemoryRouter>);
  expect(await screen.findByText(/Kênh gửi liên kết khôi phục chưa được cấu hình/)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Tạo liên kết khôi phục' })).not.toBeInTheDocument();
});
it('only asks for Authenticator OTP on reset when the user says two-step protection was enabled', async () => {
  window.history.replaceState(null, '', '/reset-password#test-reset-token');
  render(<MemoryRouter initialEntries={['/reset-password']}><PasswordRecoveryPage /></MemoryRouter>);
  expect(screen.queryByLabelText('Mã OTP từ ứng dụng')).not.toBeInTheDocument();
  await userEvent.click(screen.getByLabelText('Tôi đã bật bảo vệ hai bước bằng Authenticator'));
  expect(screen.getByLabelText('Mã OTP từ ứng dụng')).toBeInTheDocument();
  expect(screen.getByText(/Mã không được gửi qua SMS hay email/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Không mở được ứng dụng? Dùng mã khôi phục' }));
  expect(screen.getByLabelText('Mã khôi phục đã lưu')).toBeInTheDocument();
  expect(window.location.hash).toBe('');
});
it('uses a different badge color and readable text for every ticket state', () => {
  render(<>{['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'].map(status => <TicketStatusBadge key={status} status={status} />)}</>);
  expect(screen.getByText('Mới gửi')).toHaveClass('ticket-status-open');
  expect(screen.getByText('Đang xử lý')).toHaveClass('ticket-status-in_progress');
  expect(screen.getByText('Đã phản hồi kết quả')).toHaveClass('ticket-status-resolved');
  expect(screen.getByText('Đã đóng')).toHaveClass('ticket-status-closed');
});
