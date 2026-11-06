import { Link } from 'react-router';
import { useLoad } from '../../../shared/hooks/useLoad';
import { fraudApi, riskLabels, type RiskLevel } from '../api/fraudApi';
import { Button, ErrorBanner, Loading } from '../../../shared/components/ui';
import { dateTime } from '../../../shared/lib/format';
export function FraudDashboardPage() {
  const load = useLoad(signal => fraudApi.summary(signal));
  const data = load.data;
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">GIÁM SÁT GIAO DỊCH</span>
        <h1>Tổng quan rủi ro</h1>
        <p>Theo dõi cảnh báo và công việc điều tra.</p>
      </div>
      <Button className="secondary" onClick={load.reload}>Làm mới</Button>
    </div>
    <div className="notice info">Cảnh báo phục vụ hậu kiểm. Kết luận điều tra không tự thay đổi hoặc hoàn tiền giao dịch.</div>
    <ErrorBanner error={load.error} retry={load.reload} />
    {load.loading
      ? <Loading />
      : data &&
      <>
        <div className="risk-metrics">
          {[
            ['Chờ phân công', data.open], ['Đang điều tra', data.inReview], ['Ưu tiên cao chưa đóng', data.highPriorityOpen], ['Đã xác nhận gian lận', data.confirmedFraud],
          ].map(([label, count]) =>
            <section className="panel metric" key={label}>
              <span>{label}</span>
              <strong>{count}</strong>
            </section>)}
        </div>
        <div className="risk-columns">
          <section className="panel">
            <div className="section-heading">
              <h2>Phân bố mức rủi ro</h2>
              <Link className="text-link" to="/fraud/alerts">Tất cả cảnh báo</Link>
            </div>
            {(Object.keys(riskLabels) as RiskLevel[]).map(level =>
              <Link className="risk-bar-row" key={level} to={`/fraud/alerts?riskLevel=${level}`}>
                <span className={`risk-badge ${level.toLowerCase()}`}>{riskLabels[level]}</span>
                <span className="risk-bar">
                  <i style={{ width: `${data.totalAlerts ? data.byRiskLevel[level] / data.totalAlerts * 100 : 0}%` }} />
                </span>
                <strong>{data.byRiskLevel[level]}</strong>
              </Link>)}
            {data.totalAlerts === 0 &&
              <p className="muted">Chưa có cảnh báo từ các giao dịch đã phân tích.</p>}
          </section>
          <section className="panel">
            <h2>Tình trạng phân tích</h2>
            <dl className="risk-facts">
              <div>
                <dt>Giao dịch đã phân tích</dt>
                <dd>{data.analyzedTransfers}</dd>
              </div>
              <div>
                <dt>Đang chờ phân tích</dt>
                <dd>{data.pendingTransfers}</dd>
              </div>
              <div>
                <dt>Cảnh báo nhầm</dt>
                <dd>{data.falsePositive}</dd>
              </div>
              <div>
                <dt>Lần phân tích gần nhất</dt>
                <dd>{data.lastAnalyzedAt ? dateTime(data.lastAnalyzedAt) : 'Chưa có'}</dd>
              </div>
            </dl>
            <p className="muted">Điểm quy tắc là chỉ báo điều tra, không phải xác suất gian lận.</p>
          </section>
        </div>
      </>}
  </>;
}
