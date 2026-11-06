import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { SecurityCenterPage } from '../../features/security/pages/SecurityCenterPage';
import { PersonalLimitsPage } from '../../features/limits/pages/PersonalLimitsPage';
import { selfServiceApi as api } from '../../shared/api/selfServiceApi';
vi.mock('../../shared/api/selfServiceApi', () => ({
  selfServiceApi: {
    security: vi.fn(),
    sessions: vi.fn(),
    setup: vi.fn(),
    enable: vi.fn(),
    revoke: vi.fn(),
    password: vi.fn(),
    limitSettings: vi.fn(),
    limitRequests: vi.fn(),
    lowerLimit: vi.fn(),
    requestLimit: vi.fn()
  }
}));
vi.mock('qrcode', () => ({ toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,qr-test') }));
vi.mock('../../features/auth/providers/AuthProvider', () => ({ useAuth: () => ({ me: { user: { roles: ['CUSTOMER'] } }, logout: vi.fn() }) }));
vi.mock('../../features/accounts/providers/AccountsProvider', () => ({
  useAccounts: () => ({
    accounts: [{ id: 'mine', accountNumber: '101234' }],
    error: null,
    loading: false,
    refresh: vi.fn()
  })
}));
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.security).mockResolvedValue({ totpEnabled: false, recoveryCodesRemaining: 0 });
  vi.mocked(api.sessions).mockResolvedValue([]);
  vi.mocked(api.limitRequests).mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true });
});
it('guides OTP setup through password, QR, code confirmation and saving recovery codes', async () => {
  vi.mocked(api.setup).mockResolvedValue({ secret: 'SECRETKEY', uri: 'otpauth://test' });
  vi.mocked(api.enable).mockResolvedValue({ recoveryCodes: ['recovery-one', 'recovery-two'] });
  render(<MemoryRouter><SecurityCenterPage /></MemoryRouter>);
  const setup = await screen.findByRole('button', { name: 'Bật bảo vệ hai bước' });
  expect(screen.queryByLabelText('Mật khẩu hiện tại')).not.toBeInTheDocument();
  await userEvent.click(setup);
  expect(api.setup).not.toHaveBeenCalled();
  await userEvent.type(screen.getByLabelText('Mật khẩu hiện tại'), 'my-current-password');
  await userEvent.click(screen.getByRole('button', { name: 'Tiếp tục thiết lập' }));
  expect(await screen.findByRole('img', { name: /Mã QR/ })).toBeInTheDocument();
  expect(api.enable).not.toHaveBeenCalled();
  await userEvent.click(screen.getByText('Không quét được QR? Nhập khóa thủ công'));
  expect(screen.getByText('SECRETKEY')).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: 'Tôi đã thêm tài khoản' }));
  await userEvent.type(screen.getByLabelText('Mã OTP 6 chữ số'), '123456');
  await userEvent.click(screen.getByRole('button', { name: 'Xác nhận bật bảo vệ' }));
  expect(await screen.findByText(/recovery-one/)).toBeInTheDocument();
  expect(api.setup).toHaveBeenCalledWith('my-current-password', '');
  expect(api.enable).toHaveBeenCalledWith('123456');
  expect(screen.getByRole('button', { name: 'Hoàn tất' })).toBeDisabled();
  await userEvent.click(screen.getByLabelText('Tôi đã lưu các mã ở nơi riêng tư'));
  expect(screen.getByRole('button', { name: 'Hoàn tất' })).toBeEnabled();
});
it('keeps password verification in the change-password dialog and hides OTP when not enabled', async () => {
  render(<MemoryRouter><SecurityCenterPage /></MemoryRouter>);
  await userEvent.click(await screen.findByRole('button', { name: /^Đổi mật khẩu$/ }));
  expect(screen.getByRole('dialog', { name: 'Đổi mật khẩu' })).toBeInTheDocument();
  expect(screen.getByLabelText('Mật khẩu hiện tại')).toBeInTheDocument();
  expect(screen.getByLabelText('Mật khẩu mới')).toBeInTheDocument();
  expect(screen.getByLabelText('Nhập lại mật khẩu mới')).toBeInTheDocument();
  expect(screen.queryByLabelText('Mã OTP từ ứng dụng')).not.toBeInTheDocument();
});
it('submits an increase for approval instead of applying it directly', async () => {
  vi.mocked(api.limitSettings).mockResolvedValue({
    effective: {
      accountId: 'mine',
      perTransferAmount: '500',
      dailyAmount: '1000',
      dailyRemaining: '1000',
      dailyUsed: '0',
      currency: 'VND',
      date: '2026-10-31',
      timezone: 'Asia/Ho_Chi_Minh',
      defaultApplied: false
    }, ceiling: { perTransferAmount: '10000', dailyAmount: '20000' }
  });
  vi.mocked(api.requestLimit).mockResolvedValue({} as Awaited<ReturnType<typeof api.requestLimit>>);
  render(<MemoryRouter><PersonalLimitsPage /></MemoryRouter>);
  const per = await screen.findByLabelText('Mỗi lần chuyển (VND)');
  await userEvent.clear(per);
  await userEvent.type(per, '600');
  await userEvent.type(screen.getByLabelText('Lý do đề nghị tăng'), 'Need higher limit');
  await userEvent.click(screen.getByRole('button', { name: 'Gửi yêu cầu tăng hạn mức' }));
  expect(await screen.findByRole('status')).toHaveTextContent('Đã gửi yêu cầu tăng hạn mức');
  expect(api.requestLimit).toHaveBeenCalledWith({ accountId: 'mine', perTransferAmount: '600', dailyAmount: '1000', reason: 'Need higher limit' });
  expect(api.lowerLimit).not.toHaveBeenCalled();
});
