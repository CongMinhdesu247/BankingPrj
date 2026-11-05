import { useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router';
import { ArrowRight, CheckCircle2, Eye, EyeOff, UserRoundPlus } from 'lucide-react';
import { useAuth } from '../providers/AuthProvider';
import { authApi, type RegisterResult } from '../api/authApi';
import { Brand } from '../../../shared/components/Brand';
import { Button, ErrorBanner } from '../../../shared/components/ui';
import './register.css';
export function RegisterPage() {
  const { me } = useAuth();
  const [fullName, setFullName] = useState(''), [email, setEmail] = useState(''), [phone, setPhone] = useState('');
  const [username, setUsername] = useState(''), [password, setPassword] = useState(''), [confirm, setConfirm] = useState('');
  const [visible, setVisible] = useState(false), [busy, setBusy] = useState(false), [invalid, setInvalid] = useState('');
  const [error, setError] = useState<unknown>(), [result, setResult] = useState<RegisterResult>();
  if (me) return <Navigate to="/" replace />;
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return; setInvalid(''); setError(undefined);
    if (password !== confirm) { setInvalid('Mật khẩu nhập lại chưa khớp.'); return; }
    setBusy(true);
    try {
      const created = await authApi.register({ fullName: fullName.trim(), email: email.trim(), phone: phone.trim() || null, username: username.trim().toLowerCase(), password });
      setResult(created); setPassword(''); setConfirm('');
    } catch (reason) { setError(reason); } finally { setBusy(false); }
  }
  return <main className="registration-shell"><section className="panel registration-panel"><Link to="/login" className="brand-link"><Brand /></Link>
    {result ? <div className="registration-success"><CheckCircle2 size={44} /><h1>Tạo tài khoản thành công</h1><p role="status">Bạn đã có tài khoản khách hàng và có thể đăng nhập ngay.</p><dl><div><dt>Tên đăng nhập</dt><dd>{result.username}</dd></div><div><dt>Số tài khoản VND</dt><dd>{result.accountNumber}</dd></div><div><dt>Số dư ban đầu</dt><dd>0 ₫</dd></div></dl><Link className="button primary" to="/login" state={{ registeredUsername: result.username }}>Đăng nhập ngay <ArrowRight size={18} /></Link></div> : <>
      <div className="registration-heading"><span className="recovery-heading-icon"><UserRoundPlus size={25} /></span><h1>Tạo tài khoản khách hàng</h1><p>Đăng ký để quản lý tài khoản và sử dụng các tiện ích ngân hàng.</p></div>
      <div className="notice info registration-demo-note">Môi trường mô phỏng. Tài khoản VND được tạo với số dư 0, không giao dịch tiền thật.</div>
      <ErrorBanner error={error} />{invalid && <p className="notice error" role="alert">{invalid}</p>}
      <form className="service-form" onSubmit={event => void submit(event)}>
        <fieldset disabled={busy} className="registration-fields"><legend className="registration-sr-only">Thông tin đăng ký</legend>
          <label>Họ và tên<input required minLength={2} maxLength={150} autoComplete="name" value={fullName} onChange={event => setFullName(event.target.value)} placeholder="Nguyễn Văn An" /></label>
          <div className="registration-row"><label>Email<input required type="email" maxLength={254} autoComplete="email" value={email} onChange={event => setEmail(event.target.value)} placeholder="ban@example.com" /></label><label>Số điện thoại (không bắt buộc)<input type="tel" pattern="[+]?[0-9]{8,15}" maxLength={16} autoComplete="tel" value={phone} onChange={event => setPhone(event.target.value)} /></label></div>
          <label>Tên đăng nhập<input required minLength={3} maxLength={100} pattern="[A-Za-z0-9][A-Za-z0-9._\-]{2,99}" autoComplete="username" autoCapitalize="none" spellCheck={false} value={username} onChange={event => setUsername(event.target.value)} aria-describedby="register-username-help" /></label><p id="register-username-help" className="field-help">3–100 ký tự: chữ không dấu, số, dấu chấm, gạch dưới hoặc gạch ngang.</p>
          <label htmlFor="register-password">Mật khẩu</label><div className="registration-password"><input id="register-password" required type={visible ? 'text' : 'password'} minLength={12} maxLength={128} autoComplete="new-password" value={password} onChange={event => setPassword(event.target.value)} aria-describedby="register-password-help" /><Button className="icon-button ghost" aria-label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} onClick={() => setVisible(value => !value)}>{visible ? <EyeOff size={19} /> : <Eye size={19} />}</Button></div><p id="register-password-help" className="field-help">Từ 12–128 ký tự. Không dùng lại mật khẩu ở website khác.</p>
          <label>Nhập lại mật khẩu<input required type={visible ? 'text' : 'password'} minLength={12} maxLength={128} autoComplete="new-password" value={confirm} onChange={event => setConfirm(event.target.value)} aria-invalid={!!invalid} /></label>
        </fieldset>
        <Button type="submit" className="primary registration-submit" loading={busy}>Tạo tài khoản <ArrowRight size={18} /></Button>
      </form><p className="registration-login">Đã có tài khoản? <Link className="text-link" to="/login">Đăng nhập</Link></p>
    </>}
  </section></main>;
}
