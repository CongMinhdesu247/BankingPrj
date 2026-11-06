import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { expect, it, vi } from 'vitest';
import { AuthProvider } from '../providers/AuthProvider';
import { ProtectedRoute } from '../../../app/routes/ProtectedRoute';
import { authApi } from '../api/authApi';
import type { Me, TokenResponse } from '../../../shared/types/api';
import { LoginPage } from './LoginPage';
vi.mock('../api/authApi', () => ({ authApi: { login: vi.fn(), me: vi.fn() } }));
function Destination() {
  const location = useLocation();
  return <div>Restored: {location.pathname}{location.search}</div>;
}
it('returns to the protected pathname and query after login, rather than racing a dashboard redirect', async () => {
  const me = { user: { id: '00000000-0000-0000-0000-000000000001', username: 'test.customer', roles: ['CUSTOMER'] }, customer: null } as unknown as Me;
  vi.mocked(authApi.login).mockResolvedValue({ accessToken: 'memory-only', expiresIn: 900, user: me.user } as TokenResponse);
  vi.mocked(authApi.me).mockResolvedValue(me);
  render(<MemoryRouter initialEntries={['/transfer?source=original-account']}>
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="/transfer" element={<Destination />} />
        </Route>
        <Route path="/" element={<div>Unexpected dashboard</div>} />
      </Routes>
    </AuthProvider>
  </MemoryRouter>);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Tên đăng nhập'), 'test.customer');
  await user.type(screen.getByLabelText('Mật khẩu'), 'test-password');
  await user.click(screen.getByRole('button', { name: /^Đăng nhập$/ }));
  expect(await screen.findByText('Restored: /transfer?source=original-account')).toBeInTheDocument();
  expect(screen.queryByText('Unexpected dashboard')).not.toBeInTheDocument();
});
