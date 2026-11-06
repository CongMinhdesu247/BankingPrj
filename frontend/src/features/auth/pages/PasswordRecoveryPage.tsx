import { useEffect, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router';
import { Copy, FolderOpen, KeyRound } from 'lucide-react';
import { Button, ErrorBanner, Loading } from '../../../shared/components/ui';
import { SecondFactorInput } from '../../security/components/SecondFactorInput';
import { useLoad } from '../../../shared/hooks/useLoad';
import { ApiFailure } from '../../../shared/api/httpClient';
import { selfServiceApi as api } from '../../../shared/api/selfServiceApi';
export function PasswordRecoveryPage() {
  const location = useLocation();
  const reset = location.pathname === '/reset-password';
  const [token] = useState(() => window.location.hash.slice(1));
  const options = useLoad(signal => api.recoveryOptions(signal));
  const [username, setUsername] = useState(''),
    [password, setPassword] = useState(''),
    [confirm, setConfirm] = useState(''),
    [otp, setOtp] = useState(''),
    [needsOtp, setNeedsOtp] = useState(false);
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false),
    [done, setDone] = useState(false),
    [invalid, setInvalid] = useState(''),
    [copied, setCopied] = useState(false);
  useEffect(() => {
    if (reset && window.location.hash) window.history.replaceState(null, '', window.location.pathname);
  }, [reset]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    setInvalid('');
    if (reset && password !== confirm) {
      setInvalid('Mật khẩu nhập lại chưa khớp.');
      return;
    }
    setBusy(true);
    setError(undefined);
    try {
      if (reset) {
        await api.reset(token, password, needsOtp ? otp : '');
        setPassword('');
        setConfirm('');
        setOtp('');
      }
      else { await api.forgot(username.trim()); }
      setDone(true);
    }
    catch (reason) {
      setError(reason);
      if (reset && reason instanceof ApiFailure && reason.code === 'SECURITY_VERIFICATION_FAILED' && !needsOtp) setNeedsOtp(true);
    }
    finally { setBusy(false); }
  }
  async function copyPath() {
    try {
      await navigator.clipboard.writeText(options.data?.localMailboxPath ?? '');
      setCopied(true);
    }
    catch { setInvalid('Bạn có thể chọn đường dẫn bên dưới và sao chép thủ công.'); }
  }
  const local = options.data?.deliveryMode === 'LOCAL_MAILBOX';
  return <main className="recovery-shell">
    <section className="panel recovery-panel">
      <Link className="text-link" to="/login">← Quay lại đăng nhập</Link>
      <span className="recovery-heading-icon"><KeyRound size={26} /></span>
      <h1>{reset ? 'Đặt mật khẩu mới' : 'Quên mật khẩu?'}</h1>
      <p className="dialog-intro">
        {reset
          ? 'Liên kết có hiệu lực 15 phút và chỉ dùng một lần. Nhập mật khẩu mới để lấy lại quyền truy cập.'
          : 'Bạn sẽ dùng một liên kết đặt lại mật khẩu. Thao tác này không gửi mã OTP.'}
      </p>
      <ErrorBanner error={error} />
      {invalid && <p role="alert" className="notice error">{invalid}</p>}
      {reset
        ? done
          ? <div className="notice info" role="status">Đã đặt lại mật khẩu và đăng xuất các thiết bị cũ. <Link className="text-link" to="/login">Đăng nhập bằng mật khẩu mới</Link></div>
          : !token
            ? <div className="notice error">Liên kết không hợp lệ hoặc đã bị mất khi tải lại trang. <Link className="text-link" to="/forgot-password">Yêu cầu liên kết mới</Link></div>
            : <form className="service-form" onSubmit={submit}>
              <label>Mật khẩu mới<input
                disabled={busy}
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                value={password}
                required
                onChange={e => setPassword(e.target.value)} /></label>
              <p className="field-help">Ít nhất 12 ký tự.</p>
              <label>Nhập lại mật khẩu mới<input
                disabled={busy}
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                value={confirm}
                required
                onChange={e => setConfirm(e.target.value)} /></label>
              <label className="checkbox-label"><input
                type="checkbox"
                disabled={busy}
                checked={needsOtp}
                onChange={e => {
                  setNeedsOtp(e.target.checked);
                  setOtp('');
                }} />Tôi đã bật bảo vệ hai bước bằng Authenticator</label>
              <p className="field-help">Nếu chưa từng kết nối ứng dụng Authenticator, bỏ chọn mục này. Bạn không cần mã OTP.</p>
              {needsOtp && <SecondFactorInput value={otp} onChange={setOtp} disabled={busy} />}
              <Button type="submit" className="primary" loading={busy}>Lưu mật khẩu mới</Button>
            </form>
        : options.loading
          ? <Loading />
          : options.error
            ? <ErrorBanner error={options.error} retry={options.reload} />
            : <>
              {local
                ? <div className="recovery-local-note">
                  <FolderOpen size={20} />
                  <div>
                    <strong>Bản thử nghiệm trên máy này</strong>
                    <p>Chưa gửi email hoặc SMS thật. Liên kết khôi phục được lưu trong một tệp thư trên máy tính.</p>
                  </div>
                </div>
                : <div className="notice info">Kênh gửi liên kết khôi phục chưa được cấu hình. Vui lòng liên hệ người quản trị để được hỗ trợ.</div>}
              {done
                ? <>
                  <p role="status" className="notice info">Đã tiếp nhận yêu cầu. Nếu tên đăng nhập hợp lệ, một thư khôi phục mới sẽ xuất hiện trong thư mục bên dưới.</p>
                  <Button className="secondary small" onClick={() => {
                    setDone(false);
                    setError(undefined);
                  }}>Tạo liên kết mới</Button>
                </>
                : local &&
                <form className="service-form" onSubmit={submit}>
                  <label>Tên đăng nhập<input
                    disabled={busy}
                    autoComplete="username"
                    required
                    maxLength={100}
                    value={username}
                    onChange={e => setUsername(e.target.value)} /></label>
                  <Button type="submit" className="primary" loading={busy}>Tạo liên kết khôi phục</Button>
                </form>}
              {local &&
                <section className="recovery-instructions" aria-label="Cách lấy liên kết khôi phục">
                  <h2>Lấy liên kết ở đâu?</h2>
                  <ol>
                    <li>Nhập tên đăng nhập và chọn <strong>Tạo liên kết khôi phục</strong>.</li>
                    <li>Mở File Explorer trên máy tính (Windows: <kbd>Win</kbd> + <kbd>E</kbd>). Dán đường dẫn thư mục dưới đây vào thanh địa chỉ.</li>
                    <li>Mở tệp <code>reset-….txt</code> mới nhất, kiểm tra đúng tên đăng nhập và sao chép liên kết trong tệp vào trình duyệt.</li>
                    <li>Đặt mật khẩu mới. Liên kết hết hạn sau 15 phút; nếu hết hạn, gửi lại yêu cầu.</li>
                  </ol>
                  <code className="recovery-folder">{options.data?.localMailboxPath}</code>
                  <Button className="secondary small" onClick={() => void copyPath()}>
                    <Copy size={15} />
                    {copied ? 'Đã sao chép đường dẫn' : 'Sao chép đường dẫn thư mục'}
                  </Button>
                </section>}
              <details className="recovery-otp-help">
                <summary>Khi nào tôi mới cần mã OTP?</summary>
                <p>Chỉ cần nếu trước đó bạn đã bật bảo vệ hai bước trong Trung tâm bảo mật. Mở Authenticator trên điện thoại, chọn tài khoản Banking để lấy mã 6 chữ số. Nếu không mở được ứng dụng, dùng mã khôi phục đã lưu khi thiết lập.</p>
              </details>
            </>}
    </section>
  </main>;
}
