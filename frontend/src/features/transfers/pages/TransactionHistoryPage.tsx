import { Filter, History } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { useAccounts } from '../../accounts/providers/AccountsProvider';
import { TransactionList } from '../components/TransactionList';
import { Button, EmptyState, ErrorBanner, FieldError, Loading, Pagination } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { vietnamDayStart, vietnamNextDayStart } from '../../../shared/lib/format';
import { transactionApi } from '../api/transactionApi';
export function TransactionHistoryPage() {
  const { accounts } = useAccounts();
  const [page, setPage] = useState(0);
  const [accountId, setAccountId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [filter, setFilter] = useState({ accountId: '', from: '', to: '' });
  const [invalid, setInvalid] = useState('');
  const resource = useLoad(signal => transactionApi.history({
    page,
    accountId: filter.accountId || undefined,
    from: filter.from ? vietnamDayStart(filter.from) : undefined,
    to: filter.to ? vietnamNextDayStart(filter.to) : undefined
  }, signal), [page, filter]);
  function apply(event: FormEvent) {
    event.preventDefault();
    if (from && to && from > to) {
      setInvalid('Ngày bắt đầu không được sau ngày kết thúc.');
      return;
    }
    setInvalid('');
    setPage(0);
    setFilter({ accountId, from, to });
  }
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">MỖI GIAO DỊCH, ĐỀU RÕ RÀNG</span>
        <h1>Lịch sử giao dịch</h1>
        <p>Tra cứu tiền vào, tiền ra và chi tiết từng giao dịch.</p>
      </div>
      <span className="date-pill"><History size={16} />Giờ Việt Nam · GMT+7</span>
    </div>
    <form className="panel history-filters" onSubmit={apply}>
      <label>Tài khoản<select value={accountId} onChange={event => setAccountId(event.target.value)}>
        <option value="">Tất cả tài khoản của bạn</option>
        {accounts.map(account => <option key={account.id} value={account.id}>{account.accountNumber}</option>)}
      </select></label>
      <label>Từ ngày<input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
      <label>Đến hết ngày<input type="date" value={to} onChange={event => setTo(event.target.value)} /></label>
      <Button type="submit" className="primary"><Filter size={17} />Lọc giao dịch</Button>
      <FieldError>{invalid}</FieldError>
    </form>
    <section className="panel recent-panel">
      <div className="section-heading">
        <h2>Danh sách giao dịch</h2>
        <span className="muted">
          {resource.data ? `${resource.data.totalElements} giao dịch` : ''}
        </span>
      </div>
      <ErrorBanner error={resource.error} retry={resource.reload} />
      {resource.loading
        ? <Loading />
        : resource.data?.content.length
          ? <>
            <TransactionList transactions={resource.data.content} ownedIds={new Set(filter.accountId ? [filter.accountId] : accounts.map(account => account.id))} />
            <Pagination {...resource.data} onChange={setPage} />
          </>
          : !resource.error &&
          <EmptyState title="Chưa có giao dịch trong khoảng này" description="Thử chọn tài khoản hoặc khoảng ngày khác để tra cứu." />}
    </section>
  </>;
}
