import { useId, useState } from 'react';
import { ArrowDownLeft, ArrowUpRight, BarChart3, EyeOff } from 'lucide-react';
import { cashflowApi, type Cashflow } from '../api/cashflowApi';
import { useLoad } from '../../../shared/hooks/useLoad';
import { EmptyState, ErrorBanner, Loading } from '../../../shared/components/ui';
import { money, wholeVnd } from '../../../shared/lib/format';
import './cashflow.css';
const shortDate = (date: string) => date.slice(8, 10) + '/' + date.slice(5, 7);
const compact = (value: bigint) => new Intl.NumberFormat('vi-VN', { notation: 'compact', maximumFractionDigits: 1 }).format(Number(value));
function DailyChart({ data }: { data: Cashflow }) {
  const titleId = useId(); const [selectedDate, setSelectedDate] = useState('');
  const picked = data.series.findIndex(day => day.date === selectedDate);
  const index = picked >= 0 ? picked : data.series.length - 1, selected = data.series[index];
  const peak = data.series.reduce((max, day) => [wholeVnd(day.incoming), wholeVnd(day.outgoing)].reduce((a, b) => a > b ? a : b, max), 0n);
  const unit = 10n ** BigInt(Math.max(0, peak.toString().length - 1));
  const maximum = peak ? ((peak + unit - 1n) / unit) * unit : 1n;
  const left = 64, top = 18, height = 180, width = 644, slot = width / data.series.length;
  const barWidth = Math.min(16, slot * .32);
  const barHeight = (value: string) => Number(wholeVnd(value) * 10000n / maximum) / 10000 * height;
  return <><div className="cashflow-plot-scroll"><svg className="cashflow-plot" viewBox="0 0 720 246" role="img" aria-labelledby={titleId} tabIndex={0} onKeyDown={event => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); setSelectedDate(data.series[Math.max(0, Math.min(data.series.length - 1, index + (event.key === 'ArrowLeft' ? -1 : 1)))].date); }
  }}><title id={titleId}>Biểu đồ tiền vào và tiền ra theo ngày. Dùng phím mũi tên trái hoặc phải để xem từng ngày.</title>
    {[0, 1, 2, 3, 4].map(tick => { const y = top + height - tick * height / 4; return <g key={tick}><line x1={left} x2={left + width} y1={y} y2={y} stroke="#e6ebf1" strokeDasharray={tick ? '3 5' : undefined} /><text x={left - 10} y={y + 5} textAnchor="end" className="cashflow-axis">{compact(maximum * BigInt(tick) / 4n)}</text></g>; })}
    {data.series.map((day, i) => { const x = left + slot * i, credit = barHeight(day.incoming), debit = barHeight(day.outgoing); return <g key={day.date} onPointerEnter={() => setSelectedDate(day.date)} onPointerDown={() => setSelectedDate(day.date)}>
      <rect x={x + 1} y={top - 6} width={Math.max(1, slot - 2)} height={height + 12} rx={5} fill={i === index ? '#f0f4f8' : 'transparent'} />
      <rect x={x + slot / 2 - barWidth - 1} y={top + height - credit} width={barWidth} height={credit} rx={Math.min(3, credit / 2)} fill="#168269" />
      <rect x={x + slot / 2 + 1} y={top + height - debit} width={barWidth} height={debit} rx={Math.min(3, debit / 2)} fill="#8b2147" />
      {(i === 0 || i === data.series.length - 1 || i % Math.ceil(data.series.length / 6) === 0) && <text x={x + slot / 2} y={top + height + 31} textAnchor="middle" className="cashflow-axis cashflow-date">{shortDate(day.date)}</text>}
    </g>; })}
  </svg></div><p className="cashflow-mobile-hint">Vuốt ngang để xem biểu đồ · Chạm vào cột để xem số tiền</p>
    <div className="cashflow-day-detail" aria-live="polite"><strong>{shortDate(selected.date)}</strong><span><i className="cashflow-dot credit" />Vào <b>{money(selected.incoming)}</b></span><span><i className="cashflow-dot debit" />Ra <b>{money(selected.outgoing)}</b></span></div>
  </>;
}
function FlowRing({ data }: { data: Cashflow }) {
  const incoming = wholeVnd(data.incoming), outgoing = wholeVnd(data.outgoing), total = incoming + outgoing;
  const share = total ? Number(incoming * 1000n / total) / 10 : 0;
  return <><div className="cashflow-ring"><svg viewBox="0 0 220 220" role="img" aria-label={`Tỷ trọng dòng tiền: tiền vào ${share}%, tiền ra ${Math.round((100 - share) * 10) / 10}%`}>
    <circle cx={110} cy={110} r={86} fill="none" stroke="#8b2147" strokeWidth={22} />
    <circle cx={110} cy={110} r={86} fill="none" stroke="#168269" strokeWidth={22} pathLength={100} strokeDasharray={`${share} ${100 - share}`} transform="rotate(-90 110 110)" />
    <text x={110} y={104} textAnchor="middle" className="cashflow-ring-value">{new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 }).format(share)}%</text><text x={110} y={130} textAnchor="middle" className="cashflow-ring-label">Tiền vào</text>
  </svg></div><dl className="cashflow-ring-legend"><div><dt><i className="cashflow-dot credit" />Tiền vào</dt><dd>{money(data.incoming)}</dd></div><div><dt><i className="cashflow-dot debit" />Tiền ra</dt><dd>{money(data.outgoing)}</dd></div></dl></>;
}
export function CashflowCharts({ hidden, refreshKey }: { hidden: boolean; refreshKey: unknown }) {
  const [days, setDays] = useState(30);
  const load = useLoad(signal => hidden ? Promise.resolve(null) : cashflowApi.get(days, signal), [days, hidden, refreshKey]);
  const data = load.data, hasActivity = !!data && (wholeVnd(data.incoming) > 0n || wholeVnd(data.outgoing) > 0n);
  const net = data ? BigInt(data.net.split('.')[0]) : 0n;
  return <section className="cashflow-section" aria-label="Phân tích dòng tiền"><div className="cashflow-heading"><div><h2>Dòng tiền của bạn</h2><p>{data && !hidden ? shortDate(data.from) + ' – ' + shortDate(data.to) + ' · Tất cả tài khoản' : 'Theo dõi tiền vào và tiền ra trên các tài khoản của bạn.'}</p></div><div className="cashflow-periods" role="group" aria-label="Khoảng thời gian biểu đồ">{[7, 30].map(period => <button key={period} type="button" aria-pressed={days === period} onClick={() => setDays(period)}>{period} ngày</button>)}</div></div>
    {hidden ? <div className="panel cashflow-private"><EyeOff size={24} /><p>Biểu đồ dòng tiền đang được ẩn cùng số dư.</p></div> : <><ErrorBanner error={load.error} retry={load.reload} />{load.loading ? <div className="panel cashflow-loading"><Loading /></div> : data && <>
      <div className="cashflow-grid"><section className="panel cashflow-daily"><div className="cashflow-card-heading"><h3>Tiền vào & tiền ra</h3><span className="cashflow-unit">Đơn vị: VND</span></div>
        <div className="cashflow-totals"><div><span><ArrowDownLeft size={16} />Tiền vào</span><strong className="cashflow-credit">{money(data.incoming)}</strong></div><div><span><ArrowUpRight size={16} />Tiền ra</span><strong className="cashflow-debit">{money(data.outgoing)}</strong></div><div><span>Chênh lệch</span><strong className={net >= 0n ? 'cashflow-credit' : 'cashflow-debit'}>{net > 0n ? '+' : ''}{money(net)}</strong></div></div>
        {hasActivity ? <DailyChart data={data} /> : <div className="cashflow-empty"><BarChart3 size={38} /><EmptyState title="Chưa có dòng tiền trong kỳ" description="Biểu đồ sẽ hiển thị khi tài khoản có tiền vào hoặc tiền ra." /></div>}
      </section><section className="panel cashflow-breakdown"><div className="cashflow-card-heading"><h3>Cơ cấu dòng tiền</h3><span className="cashflow-period-label">{days} ngày</span></div>{hasActivity ? <FlowRing data={data} /> : <div className="cashflow-no-ring"><div /><p>Chưa có dữ liệu để tính tỷ trọng</p></div>}</section></div>
      <p className="cashflow-footnote">Tính theo giờ Việt Nam, đến thời điểm tải dữ liệu. Bao gồm vốn mở sổ và chuyển giữa tài khoản của bạn; tiền vào/ra không đồng nghĩa với thu nhập/chi tiêu.</p>
      <details className="cashflow-data-table"><summary>Xem số liệu theo ngày</summary><div className="risk-table-wrap"><table className="risk-table"><thead><tr><th>Ngày</th><th>Tiền vào</th><th>Tiền ra</th></tr></thead><tbody>{data.series.map(day => <tr key={day.date}><td>{day.date.split('-').reverse().join('/')}</td><td>{money(day.incoming)}</td><td>{money(day.outgoing)}</td></tr>)}</tbody></table></div></details>
    </>}</>}
  </section>;
}
