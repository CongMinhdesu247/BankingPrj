import { VndInput } from '../../../shared/components/VndInput';
import { AlertTriangle, ArrowDown, ArrowRight, Check, CheckCircle2, Clock3, LockKeyhole, Send, ShieldCheck, UsersRound } from 'lucide-react';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAccounts } from '../../accounts/providers/AccountsProvider';
import { useAuth } from '../../auth/providers/AuthProvider';
import { Button, EmptyState, ErrorBanner, FieldError, Loading, Modal } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { accountNumber, dateTime, money, validAmount, wholeVnd } from '../../../shared/lib/format';
import { clearMatchingPending, expiredPending, readPending, savePending, type PendingTransfer } from '../lib/pendingTransfer';
import { accountApi } from '../../accounts/api/accountApi';
import { beneficiaryApi } from '../../beneficiaries/api/beneficiaryApi';
import { ApiFailure } from '../../../shared/api/httpClient';
import { transferApi } from '../api/transferApi';
import type { Recipient, TransferPayload } from '../../../shared/types/api';
import { selfServiceApi } from '../../../shared/api/selfServiceApi';

export function TransferPage() {
  const { me } = useAuth(); const { accounts, loading, error: accountsError, refresh } = useAccounts();
  const [query] = useSearchParams(); const navigate = useNavigate();
  const [sourceId, setSourceId] = useState(''); const [destination, setDestination] = useState('');
  const [recipient, setRecipient] = useState<Recipient | null>(null); const [nickname, setNickname] = useState('');
  const [amount, setAmount] = useState(''); const [description, setDescription] = useState('');
  const [formError, setFormError] = useState(''); const [error, setError] = useState<unknown>(null);
  const [looking, setLooking] = useState(false); const [lookupError, setLookupError] = useState<unknown>(null);
  const [review, setReview] = useState<TransferPayload | null>(null);
  const [pending, setPending] = useState<PendingTransfer | null>(() => readPending(me!.user.id));
  const [busy, setBusy] = useState(false); const [discard, setDiscard] = useState(false); const [acknowledged, setAcknowledged] = useState(false);
  const mounted = useRef(true), inFlight = useRef(false), lookup = useRef<AbortController | null>(null);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; lookup.current?.abort(); }; }, []);
  const source = accounts.find(account => account.id === sourceId);
  const limits = useLoad(signal => sourceId ? accountApi.limits(sourceId, signal) : Promise.resolve(null), [sourceId, accounts]);
  const contacts = useLoad(signal => beneficiaryApi.list(0, signal), []);
  const selected = useLoad(signal => query.get('beneficiary') ? beneficiaryApi.get(query.get('beneficiary')!, signal) : Promise.resolve(null), [query.get('beneficiary')]);
  const pendingRecipient = useLoad(signal => pending ? accountApi.recipientById(pending.payload.destinationAccountId, signal) : Promise.resolve(null), [pending?.key]);
  const template = useLoad(signal => query.get('template') ? selfServiceApi.template(query.get('template')!, signal) : Promise.resolve(null), [query.get('template')]);
  useEffect(() => {
    if (!template.data || pending) return;
    const saved = template.data;
    setSourceId(saved.sourceAccountId); setDestination(saved.destinationAccountNumber);
    setRecipient(null); setAmount(saved.amount.split('.')[0]); setDescription(saved.description ?? '');
    setNickname(saved.name);
  }, [template.data, pending]);
  useEffect(() => { if (!sourceId && accounts.length) { const wanted = query.get('source'); setSourceId(accounts.find(account => account.id === wanted && account.status === 'ACTIVE')?.id ?? accounts.find(account => account.status === 'ACTIVE')?.id ?? ''); } }, [accounts, sourceId, query]);
  useEffect(() => { if (selected.data && !pending) { setDestination(selected.data.destinationAccountNumber); setNickname(selected.data.nickname ?? ''); setRecipient(null); } }, [selected.data, pending]);
  async function resolve(value = destination, name = nickname) {
    const number = value.trim(); setLookupError(null); setRecipient(null);
    if (!number || number.length > 30) { setFormError('Vui lòng nhập số tài khoản nhận, tối đa 30 ký tự.'); return; }
    lookup.current?.abort(); const controller = new AbortController(); lookup.current = controller;
    setLooking(true); setFormError(''); setNickname(name);
    try { const found = await accountApi.recipient(number, controller.signal); if (!controller.signal.aborted) setRecipient(found); }
    catch (failure) { if (!controller.signal.aborted) setLookupError(failure); }
    finally { if (!controller.signal.aborted && mounted.current) setLooking(false); }
  }
  function chooseBeneficiary(id: string) {
    const person = contacts.data?.content.find(item => item.id === id);
    if (person) { setDestination(person.destinationAccountNumber); void resolve(person.destinationAccountNumber, person.nickname ?? ''); }
  }
  function submit(event: FormEvent) {
    event.preventDefault(); if (busy || inFlight.current || pending) return; setFormError(''); setError(null);
    if (!source || source.status !== 'ACTIVE') { setFormError('Vui lòng chọn tài khoản nguồn đang hoạt động.'); return; }
    if (!recipient || recipient.accountNumber !== destination.trim()) { setFormError('Vui lòng kiểm tra tài khoản nhận trước khi tiếp tục.'); return; }
    if (recipient.id === source.id) { setFormError('Tài khoản nguồn và tài khoản nhận phải khác nhau.'); return; }
    if (recipient.status !== 'ACTIVE') { setFormError('Tài khoản nhận hiện không hoạt động.'); return; }
    if (!validAmount(amount)) { setFormError('Số tiền phải là VND nguyên lớn hơn 0, tối đa 17 chữ số.'); return; }
    const value = BigInt(amount);
    if (value > wholeVnd(source.balance)) { setFormError('Số dư tài khoản nguồn không đủ.'); return; }
    if (!limits.data) { setFormError('Chưa tải được hạn mức. Vui lòng thử tải lại trước khi chuyển.'); return; }
    if (value > wholeVnd(limits.data.perTransferAmount)) { setFormError('Số tiền vượt hạn mức mỗi lần chuyển.'); return; }
    if (value > wholeVnd(limits.data.dailyRemaining)) { setFormError('Số tiền vượt hạn mức còn lại hôm nay.'); return; }
    setReview({ sourceAccountId: source.id, destinationAccountId: recipient.id, amount: value.toString(), currency: 'VND', description: description.trim() || null });
  }
  async function send(record: PendingTransfer) {
    if (inFlight.current) return;
    inFlight.current = true; setBusy(true); setError(null);
    try {
      const result = await transferApi.send(record.payload, record.key);
      clearMatchingPending(record.ownerId, record.key); refresh();
      if (mounted.current) { setPending(null); navigate(`/transactions/${result.transaction.id}`, { replace: true, state: { justTransferred: true } }); }
    } catch (failure) {
      if (failure instanceof ApiFailure && !failure.outcomeUnknown) {
        clearMatchingPending(record.ownerId, record.key); refresh(); if (mounted.current) setPending(null);
      }
      if (mounted.current) setError(failure);
    } finally { inFlight.current = false; if (mounted.current) setBusy(false); }
  }
  function confirm() {
    if (!review || inFlight.current) return;
    const record: PendingTransfer = { version: 1, ownerId: me!.user.id, key: crypto.randomUUID(), payload: review, createdAt: new Date().toISOString() };
    if (!savePending(record)) { setReview(null); setError(new ApiFailure('STORAGE_UNAVAILABLE')); return; }
    setPending(record); setReview(null); void send(record);
  }
  const expired = pending ? expiredPending(pending) : false;
  return <><div className="page-heading"><div><span className="eyebrow">GỬI ĐI NHANH CHÓNG, KẾT NỐI GẦN HƠN</span><h1>Chuyển tiền nội bộ</h1><p>Chuyển giữa các tài khoản VND trong cùng hệ thống.</p></div><span className="date-pill"><ShieldCheck size={16} />Xác nhận trước khi gửi</span></div><ErrorBanner error={accountsError} retry={refresh} />
    {pending ? <section className="panel pending-panel"><span className={`pending-symbol ${busy ? 'processing' : ''}`}><Clock3 size={32} /></span><span className="eyebrow">{busy ? 'ĐANG XỬ LÝ YÊU CẦU' : 'YÊU CẦU ĐƯỢC LƯU TẠM'}</span><h2>{busy ? 'Đang gửi yêu cầu chuyển tiền…' : expired ? 'Cần đối chiếu lịch sử giao dịch' : 'Chưa xác định kết quả giao dịch'}</h2><p>{busy ? 'Vui lòng chờ phản hồi. Bạn không cần gửi thêm yêu cầu mới.' : expired ? 'Yêu cầu đã quá cửa sổ chống trùng 7 ngày. Không gửi lại trước khi xác định kết quả trong lịch sử.' : 'Có thể giao dịch đã được ghi nhận. Thử lại sẽ dùng nguyên mã yêu cầu và nội dung đã lưu, để tránh chuyển tiền hai lần.'}</p><div className="pending-amount">{money(pending.payload.amount)}</div><dl className="review-details"><div><dt>Tài khoản nguồn</dt><dd>{accountNumber(accounts.find(account => account.id === pending.payload.sourceAccountId)?.accountNumber ?? 'Đang tải…')}</dd></div><div><dt>Tài khoản nhận</dt><dd>{pendingRecipient.data ? accountNumber(pendingRecipient.data.accountNumber) : 'Tài khoản đã chọn trong yêu cầu'}</dd></div><div><dt>Nội dung</dt><dd>{pending.payload.description ?? 'Không có nội dung'}</dd></div><div><dt>Khởi tạo</dt><dd>{dateTime(pending.createdAt)}</dd></div></dl><ErrorBanner error={error} /><div className="button-row">{!expired && <Button className="primary" loading={busy} onClick={() => void send(pending)}>Thử lại cùng yêu cầu <ArrowRight size={17} /></Button>}<Link className={`button secondary ${busy ? 'disabled-link' : ''}`} to="/history" aria-disabled={busy} onClick={event => { if (busy) event.preventDefault(); }}>Kiểm tra lịch sử</Link></div>{!busy && <Button className="ghost small" onClick={() => { setAcknowledged(false); setDiscard(true); }}>Đóng yêu cầu đã đối chiếu</Button>}</section> : loading ? <Loading /> : !accountsError && !accounts.some(account => account.status === 'ACTIVE') ? <div className="panel"><EmptyState title="Chưa có tài khoản nguồn khả dụng" description="Bạn cần tài khoản thanh toán đang hoạt động để chuyển tiền." action={<Link className="button secondary" to="/accounts">Xem tài khoản</Link>} /></div> : !accountsError && <div className="transfer-grid"><section className="panel transfer-form-panel"><div className="section-heading"><h2>Thông tin chuyển tiền</h2><span className="step-label">01 / 02</span></div><ErrorBanner error={error} /><form onSubmit={submit} noValidate><label className="field-label" htmlFor="source-account">Từ tài khoản</label><select id="source-account" value={sourceId} onChange={event => { setSourceId(event.target.value); setFormError(''); }}><option value="" disabled>Chọn tài khoản nguồn</option>{accounts.filter(account => account.status === 'ACTIVE').map(account => <option key={account.id} value={account.id}>{accountNumber(account.accountNumber)} · {money(account.balance)}</option>)}</select>{source && <div className="balance-hint">Số dư khả dụng <strong>{money(source.balance)}</strong></div>}<div className="transfer-divider"><span /><ArrowDown size={16} /><span /></div><div className="field-title"><label className="field-label" htmlFor="destination-account">Đến tài khoản</label><Link className="text-link" to="/beneficiaries"><UsersRound size={15} />Người thụ hưởng</Link></div>{contacts.data?.content.length ? <select aria-label="Chọn người thụ hưởng đã lưu" defaultValue="" onChange={event => chooseBeneficiary(event.target.value)} disabled={looking}><option value="">Chọn từ người thụ hưởng đã lưu</option>{contacts.data.content.map(person => <option key={person.id} value={person.id}>{person.nickname || 'Người nhận'} · {person.destinationAccountNumber}</option>)}</select> : null}<div className="recipient-input"><input id="destination-account" inputMode="numeric" autoComplete="off" placeholder="Nhập số tài khoản nhận" value={destination} maxLength={30} disabled={looking} onChange={event => { lookup.current?.abort(); setDestination(event.target.value); setRecipient(null); setNickname(''); setLooking(false); setLookupError(null); }} /><Button className="secondary" loading={looking} onClick={() => void resolve()}>Kiểm tra</Button></div><ErrorBanner error={lookupError} /><ErrorBanner error={selected.error} /><ErrorBanner error={template.error} retry={template.reload} />{recipient && <div className={`recipient-confirmed ${recipient.status !== 'ACTIVE' ? 'recipient-inactive' : ''}`}><CheckCircle2 size={20} /><div><strong>{nickname || 'Tài khoản trong hệ thống'}</strong><small>{accountNumber(recipient.accountNumber)} · {recipient.status === 'ACTIVE' ? 'Đang hoạt động' : 'Hiện không hoạt động'}</small></div><span>VND</span></div>}<label className="field-label" htmlFor="transfer-amount">Số tiền</label><div className="amount-input"><VndInput id="transfer-amount" autoComplete="off" placeholder="0" value={amount} maxLength={40} onValueChange={setAmount} /><span>VND</span></div><span className="amount-preview">{validAmount(amount) ? money(amount) : 'Nhập số tiền VND nguyên; dấu chấm phân cách được thêm tự động.'}</span><div className="amount-presets">{['100000', '500000', '1000000'].map(value => <button key={value} type="button" onClick={() => setAmount(value)}>{money(value)}</button>)}</div><label className="field-label" htmlFor="transfer-description">Nội dung chuyển tiền <span className="optional">(không bắt buộc)</span></label><textarea id="transfer-description" placeholder="Nhập lời nhắn cho người nhận" value={description} onChange={event => setDescription(event.target.value)} maxLength={255} rows={2} /><span className="character-count">{description.length}/255</span><FieldError>{formError}</FieldError><Button type="submit" className="primary full-width" disabled={looking || busy}>Tiếp tục <ArrowRight size={18} /></Button><span className="form-footnote"><LockKeyhole size={14} />Bạn sẽ kiểm tra lại thông tin trước khi xác nhận.</span></form></section><aside className="transfer-aside"><section className="panel limit-panel"><span className="limit-panel-icon"><ShieldCheck size={24} /></span><h2>Hạn mức chuyển tiền</h2><p>Áp dụng cho tài khoản nguồn đang chọn.</p><ErrorBanner error={limits.error} retry={limits.reload} />{limits.loading ? <Loading /> : limits.data && <><dl><div><dt>Mỗi lần chuyển</dt><dd>{money(limits.data.perTransferAmount)}</dd></div><div><dt>Hạn mức trong ngày</dt><dd>{money(limits.data.dailyAmount)}</dd></div></dl><div className="limit-remaining"><span>Còn lại hôm nay</span><strong>{money(limits.data.dailyRemaining)}</strong><small>Đã dùng {money(limits.data.dailyUsed)}</small></div><p className="subtle-note">Ngày {limits.data.date.split('-').reverse().join('/')} · Giờ Việt Nam</p></>}</section><section className="transfer-tips"><h3><ShieldCheck size={18} />Trước khi chuyển tiền</h3><p><Check size={16} />Kiểm tra đúng số tài khoản người nhận.</p><p><Check size={16} />Số tiền và nội dung sẽ được giữ nguyên nếu bạn cần thử lại.</p><p><Check size={16} />Không gửi giao dịch mới khi kết quả còn chưa rõ.</p><span>Chuyển tiền trong môi trường mô phỏng, không sử dụng tiền thật.</span></section></aside></div>}
    {review && <Modal title="Kiểm tra thông tin" onClose={() => setReview(null)}><div className="review-heading"><span className="review-symbol"><Send size={26} /></span><p>Bạn đang chuyển</p><strong>{money(review.amount)}</strong></div><dl className="review-details"><div><dt>Từ tài khoản</dt><dd>{accountNumber(source!.accountNumber)}</dd></div><div><dt>Đến tài khoản</dt><dd>{accountNumber(recipient!.accountNumber)}</dd></div>{nickname && <div><dt>Tên gợi nhớ của bạn</dt><dd>{nickname}</dd></div>}<div><dt>Nội dung</dt><dd>{review.description || 'Không có nội dung'}</dd></div><div><dt>Loại giao dịch</dt><dd>Chuyển tiền nội bộ · VND</dd></div></dl><div className="notice info"><ShieldCheck size={19} /><span>Vui lòng kiểm tra kỹ số tài khoản và số tiền. Giao dịch sẽ được xử lý khi bạn xác nhận.</span></div><div className="modal-actions"><Button className="secondary" onClick={() => setReview(null)}>Chỉnh sửa</Button><Button className="primary" onClick={confirm} loading={busy}>Xác nhận chuyển tiền <ArrowRight size={17} /></Button></div></Modal>}
    {discard && pending && <Modal title="Đóng yêu cầu đã đối chiếu" onClose={() => setDiscard(false)}><div className="notice warning"><AlertTriangle size={22} /><p>Đóng bản lưu tạm không hủy giao dịch trên máy chủ. Chỉ tiếp tục khi bạn đã xác định kết quả trong lịch sử; một lần chuyển mới sẽ có mã yêu cầu mới.</p></div><label className="checkbox-label"><input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} />Tôi đã kiểm tra lịch sử và xác định kết quả giao dịch.</label><div className="modal-actions"><Button className="secondary" onClick={() => setDiscard(false)}>Quay lại</Button><Button className="primary" disabled={!acknowledged} onClick={() => { clearMatchingPending(pending.ownerId, pending.key); setPending(null); setDiscard(false); setError(null); refresh(); }}>Đóng bản lưu tạm</Button></div></Modal>}
  </>;
}
