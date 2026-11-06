import { ArrowUpRight, CheckCircle2, Pencil, Plus, Trash2, UsersRound } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Button, EmptyState, ErrorBanner, FieldError, Loading, Modal, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { accountNumber, dateTime, initials } from '../../../shared/lib/format';
import { accountApi } from '../../accounts/api/accountApi';
import { beneficiaryApi } from '../api/beneficiaryApi';
import type { Beneficiary, Recipient } from '../../../shared/types/api';
export function BeneficiariesPage() {
  const [page, setPage] = useState(0);
  const resource = useLoad(signal => beneficiaryApi.list(page, signal), [page]);
  const [editing, setEditing] = useState<Beneficiary | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Beneficiary | null>(null);
  const [number, setNumber] = useState('');
  const [nickname, setNickname] = useState('');
  const [recipient, setRecipient] = useState<Recipient | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [formError, setFormError] = useState('');
  const [busy, setBusy] = useState(false);
  const [looking, setLooking] = useState(false);
  const [success, setSuccess] = useState('');
  function open(item: Beneficiary | 'new') {
    setEditing(item);
    setNumber(item === 'new' ? '' : item.destinationAccountNumber);
    setNickname(item === 'new' ? '' : item.nickname ?? '');
    setRecipient(null);
    setError(null);
    setFormError('');
  }
  async function resolve() {
    if (!number.trim() || number.trim().length > 30) {
      setFormError('Vui lòng nhập số tài khoản hợp lệ.');
      return;
    }
    setLooking(true);
    setError(null);
    setFormError('');
    try { setRecipient(await accountApi.recipient(number.trim())); }
    catch (failure) { setError(failure); }
    finally { setLooking(false); }
  }
  async function save(event: FormEvent) {
    event.preventDefault();
    if (busy || looking || !editing) return;
    if (editing === 'new' && (!recipient || recipient.accountNumber !== number.trim())) {
      setFormError('Vui lòng kiểm tra tài khoản nhận trước khi lưu.');
      return;
    }
    if (nickname.trim().length > 100) {
      setFormError('Tên gợi nhớ tối đa 100 ký tự.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (editing === 'new') await beneficiaryApi.create(recipient!.id, nickname);
      else await beneficiaryApi.update(editing.id, nickname);
      setEditing(null);
      setSuccess('Đã lưu người thụ hưởng.');
      resource.reload();
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  async function remove() {
    if (!deleting || busy) return;
    setBusy(true);
    setError(null);
    try {
      await beneficiaryApi.remove(deleting.id);
      setDeleting(null);
      setSuccess('Đã xóa khỏi danh sách người thụ hưởng. Lịch sử giao dịch được giữ nguyên.');
      if (resource.data?.content.length === 1 && page > 0) setPage(value => value - 1);
      else resource.reload();
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">LƯU MỘT LẦN, THUẬN TIỆN LẦN SAU</span>
        <h1>Người thụ hưởng</h1>
        <p>Những tài khoản quen thuộc, luôn sẵn sàng để kết nối.</p>
      </div>
      <Button className="primary" onClick={() => open('new')}><Plus size={18} />Thêm người nhận</Button>
    </div>
    {success &&
      <div className="notice success" role="status">
        <CheckCircle2 size={20} />
        {success}
      </div>}
    <ErrorBanner error={resource.error} retry={resource.reload} />
    {resource.loading
      ? <Loading />
      : resource.data?.content.length
        ? <>
          <div className="section-heading">
            <span className="muted">{resource.data.totalElements} người nhận đã lưu</span>
            <span className="muted"><UsersRound size={16} /> Chỉ hiển thị danh sách của bạn</span>
          </div>
          <div className="beneficiaries-grid">
            {resource.data.content.map(person =>
              <article className="panel beneficiary-card" key={person.id}>
                <div className="beneficiary-top">
                  <span className="contact-avatar">
                    {initials(person.nickname || 'Người nhận')}
                  </span>
                  <div className="beneficiary-actions">
                    <Button className="icon-button ghost" aria-label={`Sửa ${person.nickname || person.destinationAccountNumber}`} onClick={() => open(person)}>
                      <Pencil size={16} />
                    </Button>
                    <Button className="icon-button ghost" aria-label={`Xóa ${person.nickname || person.destinationAccountNumber}`} onClick={() => {
                      setDeleting(person);
                      setError(null);
                    }}>
                      <Trash2 size={16} />
                    </Button>
                  </div>
                </div>
                <h2>{person.nickname || 'Người nhận đã lưu'}</h2>
                <p className="beneficiary-number">
                  {accountNumber(person.destinationAccountNumber)}
                </p>
                <small>Đã thêm {dateTime(person.createdAt, true)}</small>
                <Link className="button secondary full-width" to={`/transfer?beneficiary=${person.id}`}>Chuyển tiền <ArrowUpRight size={17} /></Link>
              </article>)}
          </div>
          <Pagination {...resource.data} onChange={setPage} />
        </>
        : !resource.error &&
        <div className="panel">
          <EmptyState title="Lưu những kết nối quen thuộc" description="Thêm tài khoản nhận và đặt tên gợi nhớ để chuyển tiền thuận tiện hơn." action={<Button className="primary" onClick={() => open('new')}><Plus size={17} />Thêm người nhận đầu tiên</Button>} />
        </div>}
    {editing &&
      <Modal title={editing === 'new' ? 'Thêm người thụ hưởng' : 'Sửa tên gợi nhớ'} onClose={() => setEditing(null)} busy={busy || looking}>
        <ErrorBanner error={error} />
        <form onSubmit={save} noValidate>
          <label className="field-label" htmlFor="beneficiary-number">Số tài khoản</label>
          <div className="recipient-input">
            <input
              id="beneficiary-number"
              inputMode="numeric"
              value={number}
              onChange={event => {
                setNumber(event.target.value);
                setRecipient(null);
              }}
              maxLength={30}
              readOnly={editing !== 'new'}
              disabled={looking || busy}
              placeholder="Nhập số tài khoản nhận" />
            {editing === 'new' &&
              <Button className="secondary" onClick={() => void resolve()} loading={looking} disabled={busy}>Kiểm tra</Button>}
          </div>
          {recipient &&
            <div className="recipient-confirmed">
              <CheckCircle2 size={19} />
              <div>
                <strong>Tài khoản trong hệ thống</strong>
                <small>{accountNumber(recipient.accountNumber)}</small>
              </div>
            </div>}
          <label className="field-label" htmlFor="beneficiary-nickname">Tên gợi nhớ <span className="optional">(không bắt buộc)</span></label>
          <input
            id="beneficiary-nickname"
            value={nickname}
            onChange={event => setNickname(event.target.value)}
            placeholder="Ví dụ: Gia đình, Bạn thân…"
            maxLength={100}
            disabled={busy} />
          <p className="subtle-note">Tên gợi nhớ do bạn đặt, không phải tên chủ tài khoản được ngân hàng xác nhận.</p>
          <FieldError>{formError}</FieldError>
          <div className="modal-actions">
            <Button className="secondary" onClick={() => setEditing(null)} disabled={busy || looking}>Hủy</Button>
            <Button type="submit" className="primary" loading={busy} disabled={looking}>Lưu người thụ hưởng</Button>
          </div>
        </form>
      </Modal>}
    {deleting &&
      <Modal title="Xóa người thụ hưởng?" onClose={() => setDeleting(null)} busy={busy}>
        <ErrorBanner error={error} />
        <p>Bạn muốn xóa <strong>
          {deleting.nickname || accountNumber(deleting.destinationAccountNumber)}
        </strong> khỏi danh sách đã lưu?</p>
        <p className="subtle-note">Lịch sử và các giao dịch đã thực hiện không bị xóa.</p>
        <div className="modal-actions">
          <Button className="secondary" disabled={busy} onClick={() => setDeleting(null)}>Giữ lại</Button>
          <Button className="danger" loading={busy} onClick={() => void remove()}>Xóa người thụ hưởng</Button>
        </div>
      </Modal>}
  </>;
}
