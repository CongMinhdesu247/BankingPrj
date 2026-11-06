import { useState, type FormEvent } from 'react';
import { useAccounts } from '../../accounts/providers/AccountsProvider';
import { useAuth } from '../../auth/providers/AuthProvider';
import { Button, EmptyState, ErrorBanner, Loading, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime, money, wholeVnd } from '../../../shared/lib/format';
import { selfServiceApi as api, type LimitRequest } from '../../../shared/api/selfServiceApi';
const labels: Record<string, string> = { PENDING: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối' };
export function PersonalLimitsPage() {
  const [notice, setNotice] = useState(''), [revision, setRevision] = useState(0);
  const accounts = useAccounts();
  const [selected, setSelected] = useState('');
  const id = selected || accounts.accounts[0]?.id || '';
  const settings = useLoad(signal => id ? api.limitSettings(id, signal) : Promise.resolve(null), [id]);
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">KIỂM SOÁT SỐ TIỀN CHUYỂN ĐI</span>
        <h1>Hạn mức cá nhân</h1>
        <p>Giảm hạn mức ngay hoặc gửi yêu cầu tăng trong phạm vi ngân hàng cho phép.</p>
      </div>
    </div>
    {notice && <p className="notice info" role="status">{notice}</p>}
    <section className="panel tools-filters">
      <label>Tài khoản<select value={id} onChange={e => setSelected(e.target.value)}>
        {accounts.accounts.map(a => <option key={a.id} value={a.id}>{a.accountNumber}</option>)}
      </select></label>
      <Button className="secondary" onClick={settings.reload}>Làm mới</Button>
    </section>
    <ErrorBanner error={accounts.error} retry={accounts.refresh} />
    <ErrorBanner error={settings.error} retry={settings.reload} />
    {settings.loading || accounts.loading
      ? <Loading />
      : !id
        ? <EmptyState title="Chưa có tài khoản" description="Bạn cần tài khoản để quản lý hạn mức." />
        : settings.data &&
        <>
          <div className="risk-metrics limit-metrics">
            <section className="panel metric">
              <span>Mỗi lần chuyển</span>
              <strong>{money(settings.data.effective.perTransferAmount)}</strong>
            </section>
            <section className="panel metric">
              <span>Mỗi ngày</span>
              <strong>{money(settings.data.effective.dailyAmount)}</strong>
            </section>
            <section className="panel metric">
              <span>Còn lại hôm nay</span>
              <strong>{money(settings.data.effective.dailyRemaining)}</strong>
            </section>
          </div>
          <LimitForm
            key={`${id}-${settings.data.effective.perTransferAmount}-${settings.data.effective.dailyAmount}`}
            id={id}
            per={settings.data.effective.perTransferAmount}
            daily={settings.data.effective.dailyAmount}
            ceilingPer={settings.data.ceiling.perTransferAmount}
            ceilingDaily={settings.data.ceiling.dailyAmount}
            reload={message => {
              setNotice(message);
              setRevision(value => value + 1);
              settings.reload();
            }} />
        </>}
    <LimitRequestsTable key={revision} />
  </>;
}
function LimitForm({ id, per, daily, ceilingPer, ceilingDaily, reload }: {
  id: string;
  per: string;
  daily: string;
  ceilingPer: string;
  ceilingDaily: string;
  reload: (notice: string) => void
}) {
  const [nextPer, setPer] = useState(wholeVnd(per).toString()),
    [nextDaily, setDaily] = useState(wholeVnd(daily).toString()),
    [reason, setReason] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>();
  const withinCeiling = /^[0-9]{1,17}$/.test(nextPer) && /^[0-9]{1,17}$/.test(nextDaily) && BigInt(nextPer) <= wholeVnd(ceilingPer) && BigInt(nextDaily) <= wholeVnd(ceilingDaily);
  const valid = withinCeiling && BigInt(nextPer) > 0n && BigInt(nextDaily) >= BigInt(nextPer);
  const increase = valid && (BigInt(nextPer) > wholeVnd(per) || BigInt(nextDaily) > wholeVnd(daily));
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!valid || busy) return;
    setBusy(true);
    setError(undefined);
    try {
      const body = { accountId: id, perTransferAmount: nextPer, dailyAmount: nextDaily, reason: reason.trim() };
      if (increase) {
        await api.requestLimit(body);
        setReason('');
      }
      else { await api.lowerLimit(body); }
      reload(increase
        ? 'Đã gửi yêu cầu tăng hạn mức. Hạn mức hiện tại được giữ cho đến khi admin duyệt.'
        : 'Đã áp dụng hạn mức cá nhân.');
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  return <section className="panel risk-section">
    <h2>Điều chỉnh hạn mức</h2>
    <p className="subtle-note">Trần ngân hàng: {money(ceilingPer)}/lần, {money(ceilingDaily)}/ngày. Hạn mức cá nhân không được vượt trần này.</p>
    <form className="service-form" onSubmit={submit}>
      <label>Mỗi lần chuyển (VND)<input
        required
        inputMode="numeric"
        pattern="[0-9]{1,17}"
        maxLength={17}
        value={nextPer}
        onChange={e => setPer(e.target.value)} /></label>
      <label>Mỗi ngày (VND)<input
        required
        inputMode="numeric"
        pattern="[0-9]{1,17}"
        maxLength={17}
        value={nextDaily}
        onChange={e => setDaily(e.target.value)} /></label>
      {increase &&
        <label>Lý do đề nghị tăng<textarea required maxLength={1000} value={reason} onChange={e => setReason(e.target.value)} /></label>}
      <ErrorBanner error={error} />
      {!valid &&
        <p className="notice error">Nhập số VND nguyên dương; hạn mức mỗi ngày phải từ hạn mức mỗi lần trở lên.</p>}
      <Button type="submit" className="primary" loading={busy} disabled={!valid}>
        {increase ? 'Gửi yêu cầu tăng hạn mức' : 'Áp dụng hạn mức thấp hơn'}
      </Button>
    </form>
  </section>;
}
export function LimitRequestsTable() {
  const { me } = useAuth();
  const admin = me!.user.roles.includes('ADMIN');
  const [page, setPage] = useState(0),
    [reviewing, setReviewing] = useState<LimitRequest | null>(null),
    [note, setNote] = useState(''),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>();
  const load = useLoad(signal => api.limitRequests(admin, page, signal), [admin, page]);
  async function review(approve: boolean) {
    if (!reviewing || !note.trim() || busy) return;
    setBusy(true);
    setError(undefined);
    try {
      await api.reviewLimit(reviewing.id, approve, note.trim());
      setReviewing(null);
      setNote('');
      load.reload();
    }
    catch (failure) { setError(failure); }
    finally { setBusy(false); }
  }
  return <section className="panel risk-section">
    <div className="section-heading">
      <h2>{admin ? 'Duyệt yêu cầu hạn mức' : 'Lịch sử yêu cầu tăng hạn mức'}</h2>
      <Button className="secondary small" onClick={load.reload}>Làm mới yêu cầu</Button>
    </div>
    <ErrorBanner error={load.error} retry={load.reload} />
    <ErrorBanner error={error} />
    {load.loading
      ? <Loading />
      : load.data?.content.length
        ? <>
          <div className="risk-table-wrap">
            <table className="risk-table">
              <thead>
                <tr>
                  <th>Tài khoản</th>
                  <th>Đề nghị mỗi lần / ngày</th>
                  <th>Lý do / phản hồi</th>
                  <th>Trạng thái</th>
                  <th>Ngày gửi</th>
                  {admin && <th>Duyệt</th>}
                </tr>
              </thead>
              <tbody>
                {load.data.content.map(row =>
                  <tr key={row.id}>
                    <td>
                      {row.accountNumber}
                      {admin && <small className="statement-description">{row.username}</small>}
                    </td>
                    <td>
                      {money(row.perTransferAmount)}
                      <br />
                      {money(row.dailyAmount)}
                    </td>
                    <td>
                      {row.reason}
                      {row.reviewNote &&
                        <small className="statement-description">Phản hồi: {row.reviewNote}</small>}
                    </td>
                    <td>{labels[row.status]}</td>
                    <td>{dateTime(row.createdAt)}</td>
                    {admin &&
                      <td>
                        {row.status === 'PENDING' &&
                          <Button className="secondary small" onClick={() => {
                            setReviewing(row);
                            setNote('');
                          }}>Xem xét</Button>}
                      </td>}
                  </tr>)}
              </tbody>
            </table>
          </div>
          <Pagination {...load.data} onChange={setPage} />
        </>
        : !load.error &&
        <EmptyState title="Chưa có yêu cầu" description="Yêu cầu tăng hạn mức và kết quả duyệt sẽ xuất hiện tại đây." />}
    {reviewing &&
      <div className="notice info">
        <form className="service-form" onSubmit={e => {
          e.preventDefault();
          void review(true);
        }}>
          <h3>Yêu cầu từ {reviewing.username} · {reviewing.accountNumber}</h3>
          <label>Nhận xét duyệt / từ chối<textarea required maxLength={1000} value={note} onChange={e => setNote(e.target.value)} /></label>
          <div className="button-row">
            <Button className="primary" type="submit" loading={busy}>Phê duyệt</Button>
            <Button className="secondary" disabled={busy || !note.trim()} onClick={() => void review(false)}>Từ chối</Button>
            <Button className="ghost" disabled={busy} onClick={() => setReviewing(null)}>Đóng</Button>
          </div>
        </form>
      </div>}
  </section>;
}
export function AdminLimitRequestsPage() {
  return <>
    <div className="page-heading">
      <div>
        <h1>Phê duyệt hạn mức</h1>
        <p>Chỉ yêu cầu được phê duyệt mới thay đổi hạn mức cá nhân.</p>
      </div>
    </div>
    <LimitRequestsTable />
  </>;
}
