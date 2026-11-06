import { Link, useSearchParams } from 'react-router';
import { useLoad } from '../../../shared/hooks/useLoad';
import { fraudApi, riskLabels, statusLabels, type AlertStatus, type RiskLevel } from '../api/fraudApi';
import { Button, EmptyState, ErrorBanner, Loading, Pagination } from '../../../shared/components/ui';
import { dateTime, money } from '../../../shared/lib/format';
export function FraudAlertsPage() {
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? '', riskLevel = params.get('riskLevel') ?? '';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const load = useLoad(signal => fraudApi.alerts({ page, status, riskLevel }, signal), [page, status, riskLevel]);
  const filter = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    next.delete('page');
    if (value) next.set(key, value); else next.delete(key);
    setParams(next);
  };
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">HẬU KIỂM</span>
        <h1>Cảnh báo giao dịch</h1>
        <p>Lọc, phân công và xem bằng chứng theo từng giao dịch.</p>
      </div>
      <Button className="secondary" onClick={load.reload}>Làm mới</Button>
    </div>
    <section className="panel">
      <div className="risk-filters">
        <label>Trạng thái điều tra<select value={status} onChange={event => filter('status', event.target.value)}>
          <option value="">Tất cả trạng thái</option>
          {Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <label>Mức rủi ro<select value={riskLevel} onChange={event => filter('riskLevel', event.target.value)}>
          <option value="">Tất cả mức rủi ro</option>
          {Object.entries(riskLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
      </div>
      <ErrorBanner error={load.error} retry={load.reload} />
      {load.loading
        ? <Loading />
        : load.data?.content.length
          ? <>
            <div className="risk-table-wrap">
              <table className="risk-table">
                <thead>
                  <tr>
                    <th>Giao dịch</th>
                    <th>Số tiền</th>
                    <th>Rủi ro</th>
                    <th>Điều tra</th>
                    <th>Phụ trách</th>
                  </tr>
                </thead>
                <tbody>
                  {load.data.content.map(alert =>
                    <tr key={alert.id}>
                      <td>
                        <Link className="text-link risk-reference" to={`/fraud/alerts/${alert.id}`}>
                          {alert.reference}
                        </Link>
                        <small>{dateTime(alert.transactionAt)}</small>
                      </td>
                      <td>{money(alert.amount)}</td>
                      <td>
                        <RiskBadge level={alert.riskLevel} /> <small>{alert.score}/100</small>
                      </td>
                      <td>{statusLabels[alert.status]}</td>
                      <td>{alert.assignedUsername ?? 'Chưa phân công'}</td>
                    </tr>)}
                </tbody>
              </table>
            </div>
            <Pagination {...load.data} onChange={next => {
              const copy = new URLSearchParams(params);
              copy.set('page', String(next));
              setParams(copy);
            }} />
          </>
          : !load.error &&
          <EmptyState title="Không có cảnh báo phù hợp" description="Thử đổi bộ lọc hoặc quay lại sau khi có giao dịch mới." />}
    </section>
  </>;
}
export function RiskBadge({ level }: { level: RiskLevel }) {
  return <span className={`risk-badge ${level.toLowerCase()}`}>{riskLabels[level]}</span>;
}
export function AlertStatusText({ status }: { status: AlertStatus }) { return <>{statusLabels[status]}</>; }
