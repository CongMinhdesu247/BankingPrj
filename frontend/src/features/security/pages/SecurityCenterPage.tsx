import { useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Check, Copy, Download, KeyRound, LockKeyhole, Monitor, ShieldCheck } from 'lucide-react';
import { useAuth } from '../../auth/providers/AuthProvider';
import { Button, EmptyState, ErrorBanner, Loading, Modal } from '../../../shared/components/ui';
import { SecondFactorInput } from '../components/SecondFactorInput';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime } from '../../../shared/lib/format';
import { selfServiceApi as api, type Session } from '../../../shared/api/selfServiceApi';

type Action = 'password' | 'enable' | 'disable' | 'others' | null;
function deviceName(agent: string) {
  const browser = /Edg\//.test(agent)
    ? 'Microsoft Edge'
    : /Firefox\//.test(agent)
      ? 'Firefox'
      : /Chrome\//.test(agent) ? 'Chrome' : /Safari\//.test(agent) ? 'Safari' : null;
  const platform = /Android/.test(agent)
    ? 'Android'
    : /iPhone|iPad/.test(agent)
      ? 'iPhone / iPad'
      : /Windows/.test(agent)
        ? 'Windows'
        : /Macintosh/.test(agent) ? 'macOS' : /Linux/.test(agent) ? 'Linux' : null;
  return browser
    ? `${browser}${platform ? ` trên ${platform}` : ''}`
    : agent === 'Unknown device' ? 'Thiết bị chưa xác định' : agent;
}
export function SecurityCenterPage() {
  const { me, logout } = useAuth();
  const load = useLoad(async signal => {
    const [settings, sessions] = await Promise.all([api.security(signal), api.sessions(signal)]);
    return { settings, sessions };
  });
  const [action, setAction] = useState<Action>(null),
    [revoking, setRevoking] = useState<Session | null>(null);
  const [password, setPassword] = useState(''),
    [otp, setOtp] = useState(''),
    [next, setNext] = useState(''),
    [confirm, setConfirm] = useState('');
  const [setup, setSetup] = useState<{ secret: string; qr: string } | null>(null),
    [setupStep, setSetupStep] = useState<'connect' | 'confirm'>('connect');
  const [codes, setCodes] = useState<string[]>([]),
    [codesSaved, setCodesSaved] = useState(false),
    [copied, setCopied] = useState(false);
  const [error, setError] = useState<unknown>(),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState(''),
    [localError, setLocalError] = useState('');
  const inFlight = useRef(false);
  const enabled = load.data?.settings.totpEnabled ?? false;
  const others = load.data?.sessions.filter(session => !session.current) ?? [];
  function clearProof() {
    setPassword('');
    setOtp('');
    setNext('');
    setConfirm('');
    setError(undefined);
    setLocalError('');
  }
  function open(value: Action) {
    clearProof();
    setAction(value);
  }
  function close() {
    if (busy) return;
    setAction(null);
    setRevoking(null);
    setSetup(null);
    setSetupStep('connect');
    clearProof();
  }
  async function act(work: () => Promise<void>, refresh = true) {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(undefined);
    setLocalError('');
    try {
      await work();
      if (refresh) load.reload();
    }
    catch (reason) { setError(reason); }
    finally {
      inFlight.current = false;
      setBusy(false);
    }
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (action === 'password' && next !== confirm) {
      setLocalError('Mật khẩu nhập lại chưa khớp. Vui lòng kiểm tra hai ô mật khẩu mới.');
      return;
    }
    await act(async () => {
      if (action === 'password') {
        await api.password(password, next, otp);
        clearProof();
        setAction(null);
        logout();
      }
      else if (action === 'enable') {
        const [result, qr] = await Promise.all([api.setup(password, ''), import('qrcode')]);
        const image = await qr.toDataURL(result.uri, { width: 224, margin: 2, errorCorrectionLevel: 'M' });
        setSetup({ secret: result.secret, qr: image });
        setSetupStep('connect');
        setCopied(false);
        clearProof();
        setAction(null);
      }
      else if (action === 'disable') {
        await api.disable(password, otp);
        clearProof();
        setAction(null);
        setNotice('Đã tắt xác thực hai bước và đăng xuất các thiết bị khác.');
      }
    }, action !== 'enable' && action !== 'password');
  }
  async function copySecret() {
    if (!setup) return;
    try {
      await navigator.clipboard.writeText(setup.secret);
      setCopied(true);
    }
    catch {
      setLocalError('Không sao chép tự động được. Bạn có thể chọn và sao chép khóa bên dưới.');
    }
  }
  function downloadCodes() {
    const url = URL.createObjectURL(new Blob(['Mã khôi phục Banking - mỗi mã dùng một lần. Giữ tệp này ở nơi riêng tư.\n\n' + codes.join('\n')], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = 'banking-ma-khoi-phuc.txt';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <>
    <div className="page-heading">
      <div>
        <h1>Trung tâm bảo mật</h1>
        <p>Chọn việc bạn muốn làm. Chỉ nhập thông tin khi bắt đầu thao tác.</p>
      </div>
      <Link className="button secondary" to="/security/history">Xem lịch sử đăng nhập</Link>
    </div>
    <ErrorBanner error={load.error} retry={load.reload} />
    {notice && <p role="status" className="notice info">{notice}</p>}
    {load.loading
      ? <Loading />
      : load.data &&
      <>
        <section className="panel security-actions" aria-label="Các lựa chọn bảo mật">
          <div className="security-action-row">
            <span className="security-action-icon"><LockKeyhole size={23} /></span>
            <div className="security-action-copy">
              <h2>Mật khẩu đăng nhập</h2>
              <p>Đổi mật khẩu khi bạn muốn cập nhật hoặc nghi ngờ người khác biết mật khẩu.</p>
            </div>
            <Button className="secondary" onClick={() => open('password')}>Đổi mật khẩu</Button>
          </div>
          <div className="security-action-row">
            <span className="security-action-icon"><ShieldCheck size={24} /></span>
            <div className="security-action-copy">
              <h2>Bảo vệ đăng nhập bằng mã OTP <span className={`security-state ${enabled ? 'is-enabled' : ''}`}>
                {enabled ? 'Đã bật' : 'Chưa bật'}
              </span></h2>
              <p>
                {enabled
                  ? `Sau mật khẩu, bạn nhập mã từ ứng dụng trên điện thoại. Còn ${load.data.settings.recoveryCodesRemaining} mã khôi phục dự phòng.`
                  : 'Thêm một bước xác nhận bằng ứng dụng Authenticator trên điện thoại. Bạn vẫn có thể dùng web khi chưa bật.'}
              </p>
            </div>
            <Button className={enabled ? 'secondary' : 'primary'} onClick={() => open(enabled ? 'disable' : 'enable')}>
              {enabled ? 'Tắt xác thực hai bước' : 'Bật bảo vệ hai bước'}
            </Button>
          </div>
          <div className="security-action-row">
            <span className="security-action-icon"><Monitor size={24} /></span>
            <div className="security-action-copy">
              <h2>Thiết bị đang đăng nhập</h2>
              <p>Hiện có {load.data.sessions.length} phiên đăng nhập còn hiệu lực. Kiểm tra danh sách bên dưới để đăng xuất thiết bị không nhận ra.</p>
            </div>
            <a className="button secondary" href="#security-devices">Xem thiết bị</a>
          </div>
        </section>
        <aside className="security-explainer">
          <KeyRound size={20} />
          <div>
            <h2>Mã OTP lấy ở đâu?</h2>
            <p>Nếu đã bật bảo vệ hai bước, mở ứng dụng Authenticator trên điện thoại để lấy mã 6 chữ số. Mã này <strong>không gửi qua SMS hoặc email</strong>. Nếu chưa bật, bạn không cần nhập OTP.</p>
            <p>Quên mật khẩu là một thao tác khác: bạn dùng <Link className="text-link" to="/forgot-password">liên kết đặt lại mật khẩu</Link>.</p>
          </div>
        </aside>
        <section id="security-devices" className="panel risk-section security-devices">
          <div className="section-heading">
            <div>
              <h2>Thiết bị đang đăng nhập</h2>
              <p className="field-help">Mỗi lần đăng nhập tạo một phiên; cùng một thiết bị có thể có nhiều phiên.</p>
            </div>
            <Button className="secondary" disabled={!others.length} onClick={() => open('others')}>Đăng xuất các thiết bị khác</Button>
          </div>
          {load.data.sessions.length
            ? <ul className="security-device-list">
              {load.data.sessions.map(session =>
                <li key={session.id}>
                  <Monitor size={20} />
                  <div className="security-device-info">
                    <strong>{deviceName(session.deviceLabel)}</strong>
                    {session.current &&
                      <span className="security-state is-enabled">Bạn đang dùng phiên này</span>}
                    <p>Đăng nhập lúc {dateTime(session.createdAt)} · Hết hạn {dateTime(session.expiresAt)}</p>
                  </div>
                  {!session.current &&
                    <Button className="secondary small" onClick={() => {
                      clearProof();
                      setRevoking(session);
                    }}>Đăng xuất thiết bị</Button>}
                </li>)}
            </ul>
            : <EmptyState title="Không có phiên nào trong danh sách" description="Làm mới để kiểm tra trạng thái đăng nhập hiện tại." />}
        </section>
      </>}
    {action && action !== 'others' &&
      <Modal title={action === 'password'
        ? 'Đổi mật khẩu'
        : action === 'enable' ? 'Bật bảo vệ hai bước' : 'Tắt xác thực hai bước'} busy={busy} onClose={close}>
        <p className="dialog-intro">
          {action === 'password'
            ? 'Nhập mật khẩu hiện tại và mật khẩu mới trong cùng biểu mẫu này.'
            : action === 'enable'
              ? 'Bạn cần điện thoại có Google Authenticator hoặc Microsoft Authenticator. Nhập mật khẩu để bắt đầu kết nối.'
              : 'Sau khi tắt, bạn sẽ đăng nhập chỉ bằng mật khẩu. Các thiết bị khác sẽ được đăng xuất.'}
        </p>
        <form className="service-form" onSubmit={submit}>
          <input type="hidden" autoComplete="username" name="username" value={me?.user.username ?? ''} />
          <label>Mật khẩu hiện tại<input
            autoFocus
            disabled={busy}
            required
            type="password"
            autoComplete="current-password"
            maxLength={128}
            value={password}
            onChange={e => setPassword(e.target.value)} /></label>
          {action === 'password' &&
            <>
              <label>Mật khẩu mới<input
                disabled={busy}
                required
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                value={next}
                onChange={e => setNext(e.target.value)} /></label>
              <p className="field-help">Dùng ít nhất 12 ký tự và khác mật khẩu hiện tại.</p>
              <label>Nhập lại mật khẩu mới<input
                disabled={busy}
                required
                type="password"
                autoComplete="new-password"
                minLength={12}
                maxLength={128}
                value={confirm}
                onChange={e => setConfirm(e.target.value)} /></label>
            </>}
          {enabled && action !== 'enable' &&
            <SecondFactorInput value={otp} onChange={setOtp} disabled={busy} />}
          {action === 'password' &&
            <p className="field-help">Đổi thành công sẽ đăng xuất tất cả thiết bị. Bạn cần đăng nhập lại bằng mật khẩu mới.</p>}
          <ErrorBanner error={error} />
          {localError && <p role="alert" className="field-error">{localError}</p>}
          <div className="modal-actions">
            <Button className="secondary" disabled={busy} onClick={close}>Hủy</Button>
            <Button className="primary" type="submit" loading={busy}>
              {action === 'password'
                ? 'Lưu mật khẩu mới'
                : action === 'enable' ? 'Tiếp tục thiết lập' : 'Xác nhận tắt bảo vệ'}
            </Button>
          </div>
        </form>
      </Modal>}
    {setup &&
      <Modal title={setupStep === 'connect' ? 'Kết nối ứng dụng trên điện thoại' : 'Nhập mã để hoàn tất'} busy={busy} onClose={close}>
        <ol className="security-steps" aria-label="Tiến trình bật bảo vệ hai bước">
          <li className="complete"><Check size={14} />Xác minh mật khẩu</li>
          <li aria-current={setupStep === 'connect' ? 'step' : undefined} className={setupStep === 'confirm' ? 'complete' : ''}>Kết nối ứng dụng</li>
          <li aria-current={setupStep === 'confirm' ? 'step' : undefined}>Nhập mã OTP</li>
        </ol>
        {setupStep === 'connect'
          ? <>
            <p>Mở Authenticator, nhấn <strong>thêm tài khoản (+)</strong> rồi chọn <strong>quét mã QR</strong>.</p>
            <img
              className="security-qr"
              src={setup.qr}
              width={224}
              height={224}
              alt="Mã QR để thêm tài khoản Banking vào ứng dụng Authenticator" />
            <details className="security-manual">
              <summary>Không quét được QR? Nhập khóa thủ công</summary>
              <p>Trong ứng dụng, chọn nhập khóa thiết lập. Đặt tên Banking và chọn loại dựa trên thời gian.</p>
              <code className="security-secret">{setup.secret}</code>
              <Button className="secondary small" onClick={() => void copySecret()}>
                <Copy size={15} />
                {copied ? 'Đã sao chép' : 'Sao chép khóa'}
              </Button>
            </details>
            <p className="field-help">Khóa thiết lập có hiệu lực 10 phút. Không chia sẻ QR hoặc khóa với người khác.</p>
            {localError && <p role="alert">{localError}</p>}
            <div className="modal-actions">
              <Button className="secondary" onClick={close}>Hủy thiết lập</Button>
              <Button className="primary" onClick={() => {
                setSetupStep('confirm');
                setOtp('');
                setError(undefined);
              }}>Tôi đã thêm tài khoản</Button>
            </div>
          </>
          : <form className="service-form" onSubmit={event => {
            event.preventDefault();
            void act(async () => {
              const result = await api.enable(otp);
              setCodes(result.recoveryCodes);
              setCodesSaved(false);
              setSetup(null);
              setOtp('');
              setNotice('Đã bật bảo vệ hai bước cho tài khoản.');
            });
          }}>
            <p>Mở tài khoản <strong>Banking</strong> vừa thêm trong Authenticator. Nhập mã đang hiển thị để xác nhận kết nối thành công.</p>
            <label>Mã OTP 6 chữ số<input
              autoFocus
              required
              disabled={busy}
              autoComplete="one-time-code"
              inputMode="numeric"
              pattern="[0-9]{6}"
              maxLength={6}
              value={otp}
              onChange={e => setOtp(e.target.value)} /></label>
            <p className="field-help">Mã đổi sau mỗi 30 giây. Nếu mã vừa hết hạn hoặc đã dùng, chờ mã tiếp theo.</p>
            <ErrorBanner error={error} />
            <div className="modal-actions">
              <Button className="secondary" disabled={busy} onClick={() => setSetupStep('connect')}>Quay lại mã QR</Button>
              <Button className="primary" type="submit" loading={busy}>Xác nhận bật bảo vệ</Button>
            </div>
          </form>}
      </Modal>}
    {codes.length > 0 &&
      <Modal title="Lưu mã khôi phục dự phòng" busy={!codesSaved} onClose={() => setCodes([])}>
        <p>Nếu mất điện thoại hoặc không mở được Authenticator, dùng một mã dưới đây thay cho OTP. Mỗi mã dùng một lần và chỉ hiển thị trong bước này.</p>
        <pre className="recovery-codes">{codes.join('\n')}</pre>
        <Button className="secondary" onClick={downloadCodes}><Download size={16} />Tải tệp mã khôi phục</Button>
        <label className="checkbox-label"><input type="checkbox" checked={codesSaved} onChange={e => setCodesSaved(e.target.checked)} />Tôi đã lưu các mã ở nơi riêng tư</label>
        <div className="modal-actions">
          <Button className="primary" disabled={!codesSaved} onClick={() => setCodes([])}>Hoàn tất</Button>
        </div>
      </Modal>}
    {(action === 'others' || revoking) &&
      <Modal title={revoking ? 'Đăng xuất thiết bị này?' : 'Đăng xuất các thiết bị khác?'} busy={busy} onClose={close}>
        <p>
          {revoking
            ? `Phiên trên ${deviceName(revoking.deviceLabel)} sẽ không thể tiếp tục gửi yêu cầu. Thiết bị đó cần đăng nhập lại.`
            : `Sẽ đăng xuất ${others.length} phiên khác. Phiên bạn đang sử dụng vẫn được giữ.`}
        </p>
        <ErrorBanner error={error} />
        <div className="modal-actions">
          <Button className="secondary" disabled={busy} onClick={close}>Giữ đăng nhập</Button>
          <Button className="primary" loading={busy} onClick={() => void act(async () => {
            await api.revoke(revoking?.id);
            setRevoking(null);
            setAction(null);
            setNotice('Đã đăng xuất thiết bị được chọn.');
          })}>Xác nhận đăng xuất</Button>
        </div>
      </Modal>}
  </>;
}
