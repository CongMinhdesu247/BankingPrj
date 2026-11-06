import { useState } from 'react';
import { Link } from 'react-router';
import { useAccounts } from '../providers/AccountsProvider';
import { Button, EmptyState, ErrorBanner, Loading, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { dateTime, money } from '../../../shared/lib/format';
import { downloadCsv } from '../../../shared/lib/csv';
import { customerToolsApi } from '../api/customerToolsApi';

export function StatementPage() {
  const accounts = useAccounts();
  const [selected, setSelected] = useState('');
  const accountId = selected || accounts.accounts[0]?.id || '';
  const [month, setMonth] = useState(() => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit' }).format(new Date()).slice(0, 7));
  const [page, setPage] = useState(0);
  const validMonth = /^20\d{2}-(0[1-9]|1[0-2])$/.test(month);
  const load = useLoad(signal => accountId && validMonth
    ? customerToolsApi.statement(accountId, month, page, signal)
    : Promise.resolve(null), [accountId, month, page]);
  const data = load.data;
  function exportPage() {
    if (!data) return;
    downloadCsv(`sao-ke-${data.accountNumber}-${data.month}-trang-${data.entries.page + 1}.csv`, [
      ['Tài khoản', 'Tháng', 'Mã giao dịch', 'Thời gian Việt Nam', 'Loại', 'Số tiền VND', 'Số dư sau giao dịch VND', 'Nội dung'],
      ...data.entries.content.map(row => [data.accountNumber, data.month, row.reference, dateTime(row.createdAt), row.direction === 'CREDIT' ? 'Tiền vào' : 'Tiền ra', row.amount, row.balanceAfter, row.description ?? '']),
    ]);
  }
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">QUẢN LÝ DÒNG TIỀN</span>
        <h1>Sao kê & thu chi</h1>
        <p>Theo dõi tiền vào, tiền ra và số dư theo từng tháng.</p>
      </div>
      <Button className="secondary" onClick={load.reload} disabled={load.loading}>Làm mới</Button>
    </div>
    <section className="panel tools-filters">
      <label>Tài khoản<select value={accountId} onChange={event => {
        setSelected(event.target.value);
        setPage(0);
      }}>
        {accounts.accounts.map(account => <option key={account.id} value={account.id}>{account.accountNumber}</option>)}
      </select></label>
      <label>Tháng sao kê<input
        type="month"
        min="2000-01"
        max="2099-12"
        required
        value={month}
        onChange={event => {
          setMonth(event.target.value);
          setPage(0);
        }} /></label>
    </section>
    <ErrorBanner error={accounts.error} retry={accounts.refresh} />
    <ErrorBanner error={load.error} retry={load.reload} />
    {!validMonth &&
      <p role="alert" className="notice error">Chọn tháng hợp lệ từ 01/2000 đến 12/2099.</p>}
    {accounts.loading || load.loading
      ? <Loading />
      : !accountId
        ? <EmptyState title="Chưa có tài khoản" description="Liên hệ ngân hàng để mở tài khoản trước khi xem sao kê." />
        : data &&
        <>
          <div className="risk-metrics statement-metrics">
            {[['Số dư đầu tháng', data.openingBalance], ['Tiền vào', data.incoming], ['Tiền ra', data.outgoing], ['Số dư cuối kỳ', data.closingBalance]].map(([label, amount]) =>
              <section className="panel metric" key={label}>
                <span>{label}</span>
                <strong>{money(amount)}</strong>
              </section>)}
          </div>
          <p className="subtle-note">Kỳ hiện tại tính đến thời điểm tải dữ liệu. Tiền vào/ra gồm cả chuyển giữa tài khoản của bạn và vốn mở sổ; không đồng nghĩa với thu nhập/chi tiêu. Múi giờ Việt Nam (GMT+7).</p>
          <section className="panel">
            <div className="section-heading">
              <h2>Chi tiết sao kê</h2>
              <div className="button-row">
                <Button className="secondary small" disabled={!data.entries.content.length} onClick={exportPage}>Xuất CSV trang này</Button>
                <Button className="secondary small" onClick={() => window.print()}>In sao kê</Button>
              </div>
            </div>
            {data.entries.content.length
              ? <>
                <div className="risk-table-wrap">
                  <table className="risk-table">
                    <thead>
                      <tr>
                        <th>Giao dịch</th>
                        <th>Thời gian</th>
                        <th>Tiền vào / ra</th>
                        <th>Số dư sau giao dịch</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.entries.content.map(row =>
                        <tr key={row.transactionId}>
                          <td>
                            <Link className="text-link" to={`/transactions/${row.transactionId}`}>
                              {row.reference}
                            </Link>
                            <small className="statement-description">
                              {row.description || 'Không có nội dung'}
                            </small>
                          </td>
                          <td>{dateTime(row.createdAt)}</td>
                          <td className={row.direction === 'CREDIT' ? 'statement-credit' : ''}>
                            {row.direction === 'CREDIT' ? '+' : '−'}
                            {money(row.amount)}
                          </td>
                          <td>{money(row.balanceAfter)}</td>
                        </tr>)}
                    </tbody>
                  </table>
                </div>
                <Pagination {...data.entries} onChange={setPage} />
                <p className="subtle-note">CSV và bản in chỉ chứa trang sao kê đang hiển thị ({data.entries.content.length}/{data.entries.totalElements} bút toán).</p>
              </>
              : <EmptyState title="Không có giao dịch trong tháng này" description="Chọn tháng khác để xem lịch sử. Số dư đầu tháng và cuối kỳ vẫn được đối chiếu." />}
          </section>
        </>}
  </>;
}
