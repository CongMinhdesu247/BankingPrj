import { useState, type FormEvent } from 'react';
import { useAuth } from '../../auth/providers/AuthProvider';
import { customerToolsApi } from '../../accounts/api/customerToolsApi';
import { Button, ErrorBanner } from '../../../shared/components/ui';
export function ContactEditor() {
  const { me, updateCustomer } = useAuth();
  const [email, setEmail] = useState(me?.customer?.email ?? ''), [phone, setPhone] = useState(me?.customer?.phone ?? '');
  const [busy, setBusy] = useState(false), [error, setError] = useState<unknown>(), [saved, setSaved] = useState(false);
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(undefined); setSaved(false);
    try { const customer = await customerToolsApi.contact(email.trim(), phone.trim()); updateCustomer(customer); setEmail(customer.email ?? ''); setPhone(customer.phone ?? ''); setSaved(true); }
    catch (reason) { setError(reason); } finally { setBusy(false); }
  }
  return <section className="panel risk-section"><h2>Cập nhật thông tin liên hệ</h2><p className="subtle-note">Email và số điện thoại là thông tin liên hệ, chưa được xác minh qua OTP. Để trống một trường và lưu để xóa thông tin đó.</p><form className="tools-filters" onSubmit={submit}><label>Email<input type="email" maxLength={254} value={email} disabled={busy} onChange={event => { setEmail(event.target.value); setSaved(false); }} /></label><label>Số điện thoại<input type="tel" pattern="\+?[0-9]{8,15}" maxLength={16} value={phone} disabled={busy} onChange={event => { setPhone(event.target.value); setSaved(false); }} /></label><Button type="submit" className="primary" loading={busy}>Lưu liên hệ</Button></form><ErrorBanner error={error} />{saved && <p role="status" className="notice info">Đã cập nhật thông tin liên hệ.</p>}</section>;
}
