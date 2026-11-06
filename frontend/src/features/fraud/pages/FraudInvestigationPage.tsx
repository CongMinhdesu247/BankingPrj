import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router';
import { useAuth } from '../../auth/providers/AuthProvider';
import { Button, ErrorBanner, FieldError, Loading } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime, money } from '../../../shared/lib/format';
import { fraudApi, ruleLabels, statusLabels, type AlertDetail } from '../api/fraudApi';
import { ApiFailure } from '../../../shared/api/httpClient';
import { RiskBadge } from './FraudAlertsPage';
const reasons: Record<string, string> = {
  AMOUNT_AT_OR_ABOVE_THRESHOLD: 'Số tiền đạt hoặc vượt ngưỡng',
  OUTGOING_TRANSFER_COUNT_AT_OR_ABOVE_THRESHOLD: 'Tần suất chuyển đi đạt hoặc vượt ngưỡng',
  UNSAVED_RECIPIENT_AND_HIGH_AMOUNT: 'Người nhận chưa lưu tại thời điểm chuyển, số tiền lớn',
  RECENT_BENEFICIARY_AND_HIGH_AMOUNT: 'Người thụ hưởng mới được lưu, số tiền lớn',
  ACTOR_FAILED_LOGINS_BEFORE_TRANSFER: 'Nhiều lần đăng nhập sai trước giao dịch'
};
function observedLabel(key: string, value: unknown): string {
  if (typeof value === 'boolean') return value ? 'Có' : 'Không';
  if (key === 'amount') return money(String(value));
  if (key === 'createdAt') return dateTime(String(value));
  if (key === 'beneficiaryState') return value === 'NEW_AT_TRANSFER' ? 'Đã lưu, còn mới' : 'Chưa lưu tại thời điểm chuyển';
  return String(value);
}
export function FraudInvestigationPage() {
  const { id = '' } = useParams();
  const { me } = useAuth();
  const load = useLoad(async signal => {
    const [detail, officers] = await Promise.all([fraudApi.detail(id, signal), fraudApi.officers(signal)]);
    return { detail, officers };
  }, [id]);
  const [updated, setUpdated] = useState<AlertDetail | null>(null),
    [assignee, setAssignee] = useState(''),
    [notes, setNotes] = useState('');
  const [resolution, setResolution] = useState<'CONFIRMED_FRAUD' | 'FALSE_POSITIVE'>('FALSE_POSITIVE');
  const [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false),
    [invalid, setInvalid] = useState(false),
    [conflict, setConflict] = useState(false);
  const locked = useRef(false);
  const detail = updated ?? load.data?.detail, alert = detail?.alert;
  useEffect(() => {
    setUpdated(null);
    setError(null);
    setConflict(false);
    setNotes('');
  }, [id]);
  useEffect(() => { setAssignee(alert?.assignedTo ?? ''); }, [alert?.id, alert?.version, alert?.assignedTo]);
  const refresh = () => {
    setUpdated(null);
    setError(null);
    setConflict(false);
    load.reload();
  };
  async function mutate(action: () => Promise<AlertDetail>) {
    if (locked.current || conflict) return;
    locked.current = true;
    setBusy(true);
    setError(null);
    try {
      setUpdated(await action());
      setNotes('');
    }
    catch (failure) {
      setError(failure);
      if (failure instanceof ApiFailure && failure.status === 409) setConflict(true);
    }
    finally {
      locked.current = false;
      setBusy(false);
    }
  }
  function resolve(event: FormEvent) {
    event.preventDefault();
    if (!alert || !notes.trim()) {
      setInvalid(true);
      return;
    }
    setInvalid(false);
    void mutate(() => fraudApi.resolve(id, alert.version, resolution, notes.trim()));
  }
  if (load.loading) return <Loading />;
  if (!alert || !detail) return <ErrorBanner error={load.error} retry={refresh} />;
  const terminal = ['CONFIRMED_FRAUD', 'FALSE_POSITIVE'].includes(alert.status);
  return <>
    <div className="page-heading">
      <div>
        <Link className="text-link" to="/fraud/alerts">← Danh sách cảnh báo</Link>
        <h1>Điều tra giao dịch</h1>
        <p className="risk-reference">{alert.reference}</p>
      </div>
      <Button className="secondary" onClick={refresh} disabled={busy}>Tải bản mới nhất</Button>
    </div>
    <ErrorBanner error={error} />
    {conflict &&
      <div className="notice info">Cảnh báo đã thay đổi. Hãy tải bản mới nhất trước khi tiếp tục.</div>}
    <div className="risk-columns">
      <section className="panel">
        <div className="section-heading">
          <h2>Đánh giá rủi ro</h2>
          <RiskBadge level={alert.riskLevel} />
        </div>
        <div className="risk-score">
          {alert.score}
          <small>/100</small>
        </div>
        <p className="muted">Điểm quy tắc, không phải xác suất gian lận.</p>
        <dl className="risk-facts">
          <div>
            <dt>Số tiền</dt>
            <dd>{money(alert.amount)}</dd>
          </div>
          <div>
            <dt>Thời điểm giao dịch</dt>
            <dd>{dateTime(alert.transactionAt)}</dd>
          </div>
          <div>
            <dt>Trạng thái điều tra</dt>
            <dd>{statusLabels[alert.status]}</dd>
          </div>
          <div>
            <dt>Phụ trách</dt>
            <dd>{alert.assignedUsername ?? 'Chưa phân công'}</dd>
          </div>
        </dl>
        <Link className="button secondary" to={`/staff/transactions/${alert.transactionId}`}>Xem giao dịch và ledger</Link>
      </section>
      <section className="panel">
        <h2>Xử lý cảnh báo</h2>
        {terminal
          ? <>
            <div className="notice info">Đã kết luận: {statusLabels[alert.status]}. Giao dịch gốc được giữ nguyên.</div>
            <p className="risk-notes">{alert.notes}</p>
            {alert.resolvedAt && <small className="muted">{dateTime(alert.resolvedAt)}</small>}
          </>
          : <>
            <form className="risk-form" onSubmit={event => {
              event.preventDefault();
              void mutate(() => fraudApi.assign(id, alert.version, assignee || null));
            }}>
              <label>Người phụ trách<select value={assignee} onChange={event => setAssignee(event.target.value)} disabled={busy || conflict}>
                <option value="">Chưa phân công</option>
                {load.data?.officers.map(officer =>
                  <option key={officer.id} value={officer.id}>
                    {officer.username}
                    {officer.id === me?.user.id ? ' (Bạn)' : ''}
                  </option>)}
              </select></label>
              <Button type="submit" className="secondary" disabled={busy || conflict}>Lưu phân công</Button>
            </form>
            {alert.assignedTo === me?.user.id && alert.status === 'IN_REVIEW'
              ? <form className="risk-form" onSubmit={resolve}>
                <label>Kết luận<select value={resolution} onChange={event => setResolution(event.target.value as typeof resolution)} disabled={busy || conflict}>
                  <option value="FALSE_POSITIVE">Cảnh báo nhầm</option>
                  <option value="CONFIRMED_FRAUD">Xác nhận gian lận</option>
                </select></label>
                <label>Ghi chú điều tra<textarea
                  value={notes}
                  onChange={event => setNotes(event.target.value)}
                  maxLength={2000}
                  rows={4}
                  disabled={busy || conflict} /></label>
                <FieldError>{invalid && 'Vui lòng ghi rõ lý do kết luận.'}</FieldError>
                <Button type="submit" className="primary" loading={busy} disabled={conflict}>Lưu kết luận</Button>
              </form>
              : <p className="muted">Chỉ người đang phụ trách mới có thể kết luận cảnh báo.</p>}
          </>}
      </section>
    </div>
    <section className="panel risk-section">
      <h2>Bằng chứng theo quy tắc</h2>
      <p className="muted">Ngưỡng và phiên bản được giữ tại lần phân tích này.</p>
      <div className="evidence-grid">
        {detail.matches.map(match =>
          <article className="evidence-card" key={match.id}>
            <div className="section-heading">
              <h3>{ruleLabels[match.ruleCode]}</h3>
              <strong>+{match.scoreContribution}</strong>
            </div>
            <small>Phiên bản {match.ruleVersion}</small>
            <p>{reasons[match.reason] ?? match.reason}</p>
            <dl className="risk-facts">
              {Object.entries(match.observedValue).map(([key, value]) =>
                <div key={key}>
                  <dt>
                    {({
                      amount: 'Số tiền',
                      count: 'Số lần',
                      windowSeconds: 'Cửa sổ (giây)',
                      includesCurrentTransfer: 'Bao gồm giao dịch này',
                      beneficiaryState: 'Người nhận tại thời điểm chuyển',
                      createdAt: 'Được lưu lúc'
                    } as Record<string, string>)[key] ?? key}
                  </dt>
                  <dd>{observedLabel(key, value)}</dd>
                </div>)}
            </dl>
            <p className="evidence-threshold">Ngưỡng: {match.thresholdSnapshot.amount && money(match.thresholdSnapshot.amount)}{match.thresholdSnapshot.count != null && `${match.thresholdSnapshot.count} lần`}{match.thresholdSnapshot.windowSeconds != null && ` · ${match.thresholdSnapshot.windowSeconds} giây`}</p>
          </article>)}
      </div>
    </section>
    <section className="panel risk-section">
      <h2>Lịch sử xử lý</h2>
      {detail.history.length
        ? <ol className="risk-timeline">
          {detail.history.map((change, index) =>
            <li key={index}>
              <strong>
                {change.action === 'FRAUD_ALERT_ASSIGNED' ? 'Cập nhật phân công' : 'Ghi nhận kết luận'}
              </strong>
              <span>{dateTime(change.createdAt)} · {change.actorId}</span>
            </li>)}
        </ol>
        : <p className="muted">Chưa có thao tác điều tra.</p>}
    </section>
  </>;
}
