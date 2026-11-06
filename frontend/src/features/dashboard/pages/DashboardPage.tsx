import { CashflowCharts } from '../components/CashflowCharts';
import { ArrowRight, ArrowUpRight, CalendarDays, CircleHelp, CreditCard, FileText, Plus, Send, ShieldCheck, TrendingUp, UsersRound, Wallet } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { useAccounts } from '../../accounts/providers/AccountsProvider';
import { useAuth } from '../../auth/providers/AuthProvider';
import { AccountCard } from '../../accounts/components/AccountCard';
import { TransactionList } from '../../transfers/components/TransactionList';
import { Button, EmptyState, ErrorBanner, Loading } from '../../../shared/components/ui';
import { useLoad } from '../../../shared/hooks/useLoad';
import { accountNumber, initials, money, wholeVnd } from '../../../shared/lib/format';
import { accountApi } from '../../accounts/api/accountApi';
import { beneficiaryApi } from '../../beneficiaries/api/beneficiaryApi';
import { transactionApi } from '../../transfers/api/transactionApi';
export function DashboardPage() {
  const { me } = useAuth();
  const { accounts, loading, error, refresh } = useAccounts();
  const [hidden, setHidden] = useState(false);
  const customer = me!.user.roles.includes('CUSTOMER');
  const primary = accounts.find(account => account.status === 'ACTIVE') ?? accounts[0];
  const recent = useLoad(signal => customer ? transactionApi.history({ size: 5 }, signal) : Promise.resolve(null), [customer, accounts]);
  const contacts = useLoad(signal => customer ? beneficiaryApi.list(0, signal) : Promise.resolve(null), [customer]);
  const limits = useLoad(signal => primary ? accountApi.limits(primary.id, signal) : Promise.resolve(null), [primary?.id, accounts]);
  const name = me!.customer?.fullName ?? me!.user.username;
  if (!customer) return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">KHÔNG GIAN CÁ NHÂN</span>
        <h1>Xin chào, {name}</h1>
        <p>Bạn đang đăng nhập bằng tài khoản nội bộ.</p>
      </div>
    </div>
    <div className="panel internal-panel">
      <span className="empty-icon"><ShieldCheck size={30} /></span>
      <h2>Chào mừng đến Customer Portal</h2>
      <p>Các chức năng tài khoản và chuyển tiền dành cho khách hàng cá nhân. Tài khoản {me!.user.roles.join(', ')} của bạn có thể xem hồ sơ ở đây; công cụ quản trị API nằm trong Swagger.</p>
      <div className="button-row">
        <Link className="button primary" to="/profile">Thông tin tài khoản <ArrowRight size={17} /></Link>
        <a className="button secondary" href="/swagger-ui/index.html" target="_blank" rel="noreferrer">Mở Swagger <ArrowUpRight size={17} /></a>
      </div>
    </div>
  </>;
  return <>
    <div className="page-heading">
      <div>
        <span className="eyebrow">Ngân hàng mỗi ngày</span>
        <h1>Xin chào, {name}<span className="greeting-dot">.</span></h1>
        <p>Số dư, giao dịch và những tiện ích bạn cần, ngay tại đây.</p>
      </div>
      <span className="date-pill">
        <CalendarDays size={16} />
        {new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', day: 'numeric', month: 'long', year: 'numeric' }).format(new Date())}
      </span>
    </div>
    <ErrorBanner error={error} retry={refresh} />
    {loading
      ? <div className="dashboard-skeleton" aria-label="Đang tải tổng quan">
        <div />
        <div />
        <div />
      </div>
      : !error && accounts.length === 0
        ? <div className="panel">
          <EmptyState title="Tài khoản đầu tiên đang chờ bạn" description="Bạn chưa có tài khoản thanh toán. Liên hệ nhân viên quản lý để mở tài khoản trong môi trường này." />
        </div>
        : !error &&
        <>
          <div className="overview-grid">
            <section>
              <div className="section-heading">
                <h2>Tài khoản của bạn <span className="count-pill">{accounts.length}</span></h2>
                <Link className="text-link" to="/accounts">Xem tất cả <ArrowUpRight size={15} /></Link>
              </div>
              <AccountCard account={primary} featured hidden={hidden} onToggle={() => setHidden(value => !value)} />
              <div className="total-line">
                <Wallet size={16} />
                <span>Tổng số dư {accounts.length} tài khoản</span>
                <strong>
                  {hidden ? '••••••••' : money(accounts.reduce((sum, account) => sum + wholeVnd(account.balance), 0n))}
                </strong>
              </div>
            </section>
            <section className="panel quick-actions-panel">
              <h2>Thao tác nhanh</h2>
              <p className="quick-actions-intro">Chọn tiện ích để bắt đầu.</p>
              <div className="quick-actions">
                <Link to="/transfer">
                  <span><Send size={23} /></span>
                  <strong>Chuyển tiền</strong>
                  <small>Nội bộ, tức thời</small>
                </Link>
                <Link to="/accounts">
                  <span><CreditCard size={23} /></span>
                  <strong>Tài khoản</strong>
                  <small>Quản lý số dư</small>
                </Link>
                <Link to="/beneficiaries">
                  <span><UsersRound size={23} /></span>
                  <strong>Người nhận</strong>
                  <small>Lưu để tiện hơn</small>
                </Link>
                <Link to="/statement">
                  <span><FileText size={23} /></span>
                  <strong>Sao kê & thu chi</strong>
                  <small>Theo dõi dòng tiền</small>
                </Link>
                <Link to="/templates">
                  <span><Plus size={23} /></span>
                  <strong>Mẫu chuyển tiền</strong>
                  <small>Dùng lại thuận tiện</small>
                </Link>
                <Link to="/support">
                  <span><CircleHelp size={23} /></span>
                  <strong>Tra soát & hỗ trợ</strong>
                  <small>Trao đổi với CSKH</small>
                </Link>
              </div>
              <Link className="quick-security" to="/security">
                <ShieldCheck size={19} />
                <p>Chủ động bảo vệ tài khoản<strong>Mở trung tâm bảo mật</strong></p>
                <ArrowRight size={17} />
              </Link>
            </section>
          </div>
          <div className="summary-grid">
            <div className="summary-card">
              <span className="summary-icon"><CreditCard size={20} /></span>
              <div>
                <span>Tài khoản đang hoạt động</span>
                <strong>
                  {accounts.filter(account => account.status === 'ACTIVE').length}
                  <small> / {accounts.length} tài khoản</small>
                </strong>
              </div>
            </div>
            <div className="summary-card">
              <span className="summary-icon teal"><TrendingUp size={20} /></span>
              <div>
                <span>Đã chuyển hôm nay · TK {primary.accountNumber.slice(-4)}</span>
                <strong>
                  {limits.data ? money(limits.data.dailyUsed) : limits.loading ? 'Đang tải…' : 'Chưa tải được'}
                </strong>
              </div>
            </div>
            <div className="summary-card">
              <span className="summary-icon amber"><ShieldCheck size={20} /></span>
              <div>
                <span>Hạn mức còn lại · TK {primary.accountNumber.slice(-4)}</span>
                <strong>
                  {limits.data ? money(limits.data.dailyRemaining) : limits.loading ? 'Đang tải…' : 'Chưa tải được'}
                </strong>
              </div>
            </div>
          </div>
          <ErrorBanner error={limits.error} retry={limits.reload} />
        </>}
    {!loading && !error && accounts.length > 0 &&
      <CashflowCharts hidden={hidden} refreshKey={accounts} />}
    <div className="activity-grid">
      <section className="panel recent-panel">
        <div className="section-heading">
          <div>
            <h2>Giao dịch gần đây</h2>
            <p>Theo dõi tiền vào, tiền ra và trạng thái xử lý.</p>
          </div>
          <Link className="text-link" to="/history">Xem lịch sử <ArrowRight size={16} /></Link>
        </div>
        <ErrorBanner error={recent.error} retry={recent.reload} />
        {recent.loading
          ? <Loading />
          : recent.data?.content.length
            ? <TransactionList transactions={recent.data.content} ownedIds={new Set(accounts.map(account => account.id))} />
            : !recent.error &&
            <EmptyState title="Chưa có giao dịch" description="Các giao dịch của bạn sẽ được hiển thị tại đây." action={<Link className="button secondary" to="/transfer">Chuyển tiền đầu tiên <ArrowRight size={16} /></Link>} />}
      </section>
      <aside className="panel saved-panel">
        <div className="section-heading">
          <h2>Người thụ hưởng</h2>
          <Link className="round-link" to="/beneficiaries" aria-label="Quản lý người thụ hưởng">
            <Plus size={18} />
          </Link>
        </div>
        <ErrorBanner error={contacts.error} retry={contacts.reload} />
        {contacts.loading
          ? <Loading />
          : contacts.data?.content.length
            ? <>
              <div className="saved-contacts">
                {contacts.data.content.slice(0, 4).map(person =>
                  <Link key={person.id} to={`/transfer?beneficiary=${person.id}`}>
                    <span className="contact-avatar">
                      {initials(person.nickname || 'Người nhận')}
                    </span>
                    <div>
                      <strong>{person.nickname || 'Người nhận đã lưu'}</strong>
                      <small>{accountNumber(person.destinationAccountNumber)}</small>
                    </div>
                    <ArrowUpRight size={16} />
                  </Link>)}
              </div>
              <Link className="text-link saved-all" to="/beneficiaries">Xem danh sách <ArrowRight size={15} /></Link>
            </>
            : !contacts.error &&
            <EmptyState title="Lưu người nhận quen thuộc" description="Chuyển tiền thuận tiện hơn cho những lần sau." action={<Link className="button secondary small" to="/beneficiaries"><Plus size={16} />Thêm người nhận</Link>} />}
      </aside>
    </div>
    <div className="quiet-note">
      <ShieldCheck size={18} />
      <span>Hạn mức được tính riêng theo tài khoản nguồn, theo ngày Việt Nam. <Link to="/transfer">Xem trước khi chuyển tiền</Link></span>
      <Button className="ghost small" onClick={() => {
        refresh();
        recent.reload();
        limits.reload();
      }}>Làm mới dữ liệu</Button>
    </div>
  </>;
}
