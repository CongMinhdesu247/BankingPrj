import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { Button, EmptyState, ErrorBanner, Loading, Pagination, StatusBadge } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime, accountNumber } from '../../../shared/lib/format';
import { request } from '../../../shared/api/httpClient';
import { riskLabels, type RiskLevel } from '../api/fraudApi';
import type { Page } from '../../../shared/types/api';
export interface AccountRisk { accountId: string; accountNumber: string; customerId: string; fullName: string; customerCode: string; status: string; riskLevel: RiskLevel | 'UNASSESSED'; score: number; analyzed: number; pending: number; openAlerts: number; lastAnalyzedAt: string | null }
const labels = { ...riskLabels, UNASSESSED: 'Chưa đánh giá' };
export function RiskBadge({ value }: { value: AccountRisk }) {
  return <span className={`risk-badge ${value.riskLevel.toLowerCase()}`}>{labels[value.riskLevel]}{value.riskLevel !== 'UNASSESSED' && ` · ${value.score}/100`}</span>;
}
export function AccountRiskPage() {
  const [draft, setDraft] = useState(''), [search, setSearch] = useState(''), [riskLevel, setRisk] = useState(''), [page, setPage] = useState(0);
  const load = useLoad(signal => request<Page<AccountRisk>>(`/staff/account-risk?${new URLSearchParams({ page: String(page), search, riskLevel })}`, { signal }), [page, search, riskLevel]);
  function submit(event: FormEvent) { event.preventDefault(); setSearch(draft.trim()); setPage(0); }
  return <><div className="page-heading"><div><span className="eyebrow">GIÁM SÁT THEO TÀI KHOẢN</span><h1>Rủi ro tài khoản</h1><p>Tìm tài khoản cần đối chiếu và theo dõi độ phủ phân tích.</p></div><Button className="secondary" onClick={load.reload}>Làm mới</Button></div>
    <div className="notice info">Chỉ báo dựa trên giao dịch chuyển đi trong 30 ngày: lấy điểm cảnh báo cao nhất còn mở, đang điều tra hoặc đã xác nhận gian lận; loại cảnh báo nhầm. Không đánh giá người nhận chỉ vì nhận tiền. Điểm không phải xác suất gian lận.</div>
    <form className="panel tools-filters" onSubmit={submit}><label>Tên, mã khách hàng hoặc số tài khoản<input value={draft} maxLength={150} onChange={event => setDraft(event.target.value)} /></label><label>Mức rủi ro<select value={riskLevel} onChange={event => { setRisk(event.target.value); setPage(0); }}><option value="">Tất cả mức độ</option>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label><Button type="submit" className="primary">Tìm kiếm</Button></form>
    <section className="panel"><ErrorBanner error={load.error} retry={load.reload} />{load.loading ? <Loading /> : load.data?.content.length ? <><div className="risk-table-wrap"><table className="risk-table"><thead><tr><th>Tài khoản / Chủ tài khoản</th><th>Trạng thái</th><th>Rủi ro</th><th>Phân tích / Đang chờ</th><th>Cảnh báo mở</th><th>Phân tích gần nhất</th></tr></thead><tbody>{load.data.content.map(value => <tr key={value.accountId}><td><Link className="text-link" to={`/staff/accounts/${value.accountId}`}>{accountNumber(value.accountNumber)}</Link><small className="statement-description">{value.fullName} · {value.customerCode}</small></td><td><StatusBadge status={value.status} /></td><td><RiskBadge value={value} /></td><td>{value.analyzed} / {value.pending}{value.pending > 0 && <small className="statement-description">Kết quả chưa đầy đủ</small>}</td><td>{value.openAlerts}</td><td>{value.lastAnalyzedAt ? dateTime(value.lastAnalyzedAt) : 'Chưa có'}</td></tr>)}</tbody></table></div><Pagination {...load.data} onChange={setPage} /></> : !load.error && <EmptyState title="Không có tài khoản phù hợp" description="Thử từ khóa hoặc mức rủi ro khác." />}</section>
  </>;
}
export function AccountRiskPanel({ id }: { id: string }) {
  const load = useLoad(signal => request<Page<AccountRisk>>(`/staff/account-risk?accountId=${encodeURIComponent(id)}`, { signal }), [id]);
  const value = load.data?.content[0];
  return <section className="panel risk-section"><h2>Rủi ro tài khoản · 30 ngày</h2><ErrorBanner error={load.error} retry={load.reload} />{load.loading ? <Loading /> : value && <><RiskBadge value={value} /><dl className="risk-facts"><div><dt>Giao dịch đi đã phân tích</dt><dd>{value.analyzed}</dd></div><div><dt>Đang chờ phân tích</dt><dd>{value.pending}</dd></div><div><dt>Cảnh báo đang mở</dt><dd>{value.openAlerts}</dd></div></dl><Link className="text-link" to="/staff/account-risk">Xem bảng rủi ro tài khoản →</Link></>}</section>;
}
