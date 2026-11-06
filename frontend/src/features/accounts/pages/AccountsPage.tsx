import { CreditCard, RefreshCw, Search } from 'lucide-react';
import { useState } from 'react';
import { useAccounts } from '../providers/AccountsProvider';
import { AccountCard } from '../components/AccountCard';
import { Button, EmptyState, ErrorBanner, Loading } from '../../../shared/components/ui';
export function AccountsPage() {
  const { accounts, loading, error, refresh } = useAccounts();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const filtered = accounts.filter(account => account.accountNumber.includes(search.trim()) && (!status || account.status === status));
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">TÀI CHÍNH TRONG TẦM TAY</span>
        <h1>Tài khoản của bạn</h1>
        <p>Thông tin và số dư của các tài khoản thanh toán.</p>
      </div>
      <Button className="secondary" onClick={refresh}><RefreshCw size={16} />Làm mới</Button>
    </div>
    <div className="panel filter-bar">
      <div className="input-with-icon">
        <Search size={18} />
        <input
          aria-label="Tìm số tài khoản"
          value={search}
          onChange={event => setSearch(event.target.value)}
          placeholder="Tìm theo số tài khoản"
          inputMode="numeric" />
      </div>
      <select aria-label="Lọc trạng thái tài khoản" value={status} onChange={event => setStatus(event.target.value)}>
        <option value="">Tất cả trạng thái</option>
        <option value="ACTIVE">Đang hoạt động</option>
        <option value="FROZEN">Tạm khóa</option>
        <option value="CLOSED">Đã đóng</option>
      </select>
      <span className="filter-count"><CreditCard size={16} />{filtered.length} tài khoản</span>
    </div>
    <ErrorBanner error={error} retry={refresh} />
    {loading
      ? <Loading />
      : !error && (filtered.length
        ? <div className="accounts-grid">
          {filtered.map((account, index) => <AccountCard key={account.id} account={account} featured={index === 0} />)}
        </div>
        : <div className="panel">
          <EmptyState title={accounts.length ? 'Không tìm thấy tài khoản phù hợp' : 'Bạn chưa có tài khoản thanh toán'} description={accounts.length
            ? 'Thử thay đổi số tài khoản hoặc bộ lọc trạng thái.'
            : 'Liên hệ nhân viên quản lý để mở tài khoản trong môi trường này.'} />
        </div>)}
  </>;
}
