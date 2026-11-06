import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { authApi } from '../api/authApi';
import { request, setAccessToken, setUnauthorizedHandler } from '../../../shared/api/httpClient';
import { clearPending, readPending } from '../../transfers/lib/pendingTransfer';
import type { Me, Customer } from '../../../shared/types/api';
import { Button, Modal } from '../../../shared/components/ui';
interface AuthContextValue {
  me: Me | null;
  notice: string;
  login: (username: string, password: string, otp?: string) => Promise<void>;
  logout: () => void;
  updateCustomer: (customer: Customer) => void
}
const AuthContext = createContext<AuthContextValue | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [notice, setNotice] = useState('');
  const [logoutWarning, setLogoutWarning] = useState(false);
  const expiry = useRef<ReturnType<typeof setTimeout> | null>(null);
  const expire = useCallback(() => {
    setAccessToken(null);
    setMe(null);
    setLogoutWarning(false);
    setNotice('Phiên đăng nhập đã hết hạn. Đăng nhập lại để tiếp tục và kiểm tra yêu cầu đang chờ.');
    if (expiry.current) clearTimeout(expiry.current);
  }, []);
  useEffect(() => {
    setUnauthorizedHandler(expire);
    return () => {
      setUnauthorizedHandler(undefined);
      if (expiry.current) clearTimeout(expiry.current);
      setAccessToken(null);
    };
  }, [expire]);
  async function login(username: string, password: string, otp?: string) {
    const result = otp ? await authApi.login(username, password, otp) : await authApi.login(username, password);
    setAccessToken(result.accessToken);
    try {
      const profile = await authApi.me();
      readPending(profile.user.id); // Removes pending payload if it belongs to another user.
      setMe(profile);
      setNotice('');
      if (expiry.current) clearTimeout(expiry.current);
      expiry.current = setTimeout(expire, Math.max(0, result.expiresIn * 1000 - 5000));
    }
    catch (error) {
      setAccessToken(null);
      throw error;
    }
  }
  function finishLogout() {
    if (me) void request<void>('/me/security/logout', { method: 'POST' }).catch(() => { });
    clearPending();
    setAccessToken(null);
    setMe(null);
    setNotice('');
    setLogoutWarning(false);
    if (expiry.current) clearTimeout(expiry.current);
  }
  function logout() {
    if (me && readPending(me.user.id)) {
      setLogoutWarning(true);
      return;
    }
    finishLogout();
  }
  return <AuthContext.Provider value={{
    me,
    notice,
    login,
    logout,
    updateCustomer: customer => setMe(previous => previous && previous.customer?.id === customer.id ? { ...previous, customer } : previous)
  }}>
    {children}
    {logoutWarning &&
      <Modal title="Đăng xuất khi còn yêu cầu đang chờ?" onClose={() => setLogoutWarning(false)}>
        <p>Bạn có yêu cầu chuyển tiền chưa xác định kết quả. Đăng xuất sẽ xóa bản lưu tạm trên trình duyệt, nhưng không hủy giao dịch trên máy chủ.</p>
        <p className="subtle-note">Nên kiểm tra yêu cầu và lịch sử trước. Nếu vẫn đăng xuất, hãy tra cứu lịch sử sau khi đăng nhập lại trước khi tạo lần chuyển mới.</p>
        <div className="modal-actions">
          <Button className="secondary" onClick={() => setLogoutWarning(false)}>Ở lại kiểm tra</Button>
          <Button className="danger" onClick={finishLogout}>Vẫn đăng xuất</Button>
        </div>
      </Modal>}
  </AuthContext.Provider>;
}
export function useAuth() {
  const auth = useContext(AuthContext);
  if (!auth) throw new Error('AuthProvider is required');
  return auth;
}
