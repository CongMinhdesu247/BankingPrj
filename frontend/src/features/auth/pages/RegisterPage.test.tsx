import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { beforeEach, expect, it, vi } from 'vitest';
import { RegisterPage } from './RegisterPage';
import { authApi } from '../api/authApi';
import { ApiFailure } from '../../../shared/api/httpClient';
vi.mock('../providers/AuthProvider', () => ({ useAuth: () => ({ me: null }) }));
vi.mock('../api/authApi', () => ({ authApi: { register: vi.fn() } }));
beforeEach(() => { vi.mocked(authApi.register).mockReset(); vi.mocked(authApi.register).mockResolvedValue({ username: 'new.customer', accountNumber: '1000000000001' }); });
async function fill(confirm = 'ValidPassword123!') {
  render(<MemoryRouter><RegisterPage /></MemoryRouter>); const user = userEvent.setup();
  await user.type(screen.getByLabelText('Họ và tên'), 'Nguyễn Văn An'); await user.type(screen.getByLabelText('Email', { exact: true }), 'an@example.com');
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'New.Customer'); await user.type(screen.getByLabelText('Mật khẩu', { exact: true }), 'ValidPassword123!'); await user.type(screen.getByLabelText('Nhập lại mật khẩu'), confirm);
  return user;
}
it('creates only the customer fields and displays the new zero-balance account', async () => {
  const user = await fill(); await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
  expect(await screen.findByRole('heading', { name: 'Tạo tài khoản thành công' })).toBeInTheDocument();
  expect(authApi.register).toHaveBeenCalledWith({ fullName: 'Nguyễn Văn An', email: 'an@example.com', phone: null, username: 'new.customer', password: 'ValidPassword123!' });
  expect(screen.getByText('1000000000001')).toBeInTheDocument(); expect(screen.getByText('0 ₫')).toBeInTheDocument(); expect(screen.getByRole('link', { name: 'Đăng nhập ngay' })).toHaveAttribute('href', '/login');
});
it('rejects mismatching password confirmation without calling the API', async () => {
  const user = await fill('OtherPassword123!'); await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Mật khẩu nhập lại chưa khớp'); expect(authApi.register).not.toHaveBeenCalled();
});
it('keeps entered data when the username or email is taken', async () => {
  vi.mocked(authApi.register).mockRejectedValue(new ApiFailure('REGISTRATION_CONFLICT', 409));
  const user = await fill(); await user.click(screen.getByRole('button', { name: 'Tạo tài khoản' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('đã được sử dụng'); expect(screen.getByLabelText('Email', { exact: true })).toHaveValue('an@example.com');
  expect(screen.getByRole('button', { name: 'Tạo tài khoản' })).toBeEnabled();
});
