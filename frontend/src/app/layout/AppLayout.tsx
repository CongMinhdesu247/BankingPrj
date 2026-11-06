import { ChatbotWidget } from '../../features/support/chat/ChatbotWidget';
import { ArrowUpRight, Bell, ChevronRight, CircleHelp, CreditCard, History, LayoutDashboard, LogOut, Menu, Send, ShieldCheck, UserRound, UsersRound, X } from 'lucide-react';
import { useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import { useAuth } from '../../features/auth/providers/AuthProvider';
import { initials } from '../../shared/lib/format';
import { Brand } from '../../shared/components/Brand';
import { Button } from '../../shared/components/ui';
import { useNotifications } from '../../features/notifications/providers/NotificationsProvider';
const links = [
  { path: '/', title: 'Tổng quan', icon: LayoutDashboard }, { path: '/accounts', title: 'Tài khoản', icon: CreditCard },
  { path: '/transfer', title: 'Chuyển tiền', icon: Send }, { path: '/history', title: 'Lịch sử giao dịch', icon: History },
  { path: '/templates', title: 'Mẫu chuyển tiền', icon: Send }, { path: '/limits', title: 'Hạn mức cá nhân', icon: ShieldCheck }, { path: '/support', title: 'Tra soát & hỗ trợ', icon: CircleHelp }, { path: '/statement', title: 'Sao kê & thu chi', icon: History }, { path: '/beneficiaries', title: 'Người thụ hưởng', icon: UsersRound },
];
export function AppLayout() {
  const notifications = useNotifications();
  const { me, logout } = useAuth();
  const location = useLocation();
  const [menu, setMenu] = useState(false);
  const customer = me!.user.roles.includes('CUSTOMER');
  const risk = me!.user.roles.includes('RISK_OFFICER'),
    admin = me!.user.roles.includes('ADMIN');
  const navigationLinks = [...(customer
    ? links
    : [
      ...(risk
        ? [{ path: '/fraud', title: 'Tổng quan rủi ro', icon: ShieldCheck }, { path: '/fraud/alerts', title: 'Cảnh báo giao dịch', icon: History }]
        : []),
      ...(risk || admin ? [{ path: '/staff/account-risk', title: 'Rủi ro tài khoản', icon: ShieldCheck }] : []), ...(admin || me!.user.roles.includes('BANK_STAFF')
        ? [{ path: '/staff/support', title: 'Xử lý tra soát', icon: CircleHelp }]
        : []), ...(admin
          ? [{ path: '/staff/limit-requests', title: 'Duyệt hạn mức', icon: ShieldCheck }, { path: '/fraud/rules', title: 'Cấu hình quy tắc', icon: ShieldCheck }]
          : []),
      { path: '/staff/customers', title: 'Tra cứu khách hàng', icon: UsersRound },
    ]), { path: '/notifications', title: 'Thông báo', icon: Bell }, { path: '/security', title: 'Trung tâm bảo mật', icon: ShieldCheck }];
  const navigationGroups = customer
    ? [
      { title: 'Giao dịch hằng ngày', items: navigationLinks.filter(item => ['/', '/accounts', '/transfer', '/history', '/beneficiaries'].includes(item.path)) },
      { title: 'Tiện ích của bạn', items: navigationLinks.filter(item => ['/templates', '/limits', '/statement'].includes(item.path)) },
      { title: 'Hỗ trợ & bảo mật', items: navigationLinks.filter(item => ['/support', '/notifications', '/security'].includes(item.path)) },
    ]
    : [{ title: 'Quản lý nghiệp vụ', items: navigationLinks }];
  const name = me!.customer?.fullName ?? me!.user.username;
  const current = [...navigationLinks, { path: '/profile', title: 'Thông tin cá nhân' }].sort((a, b) => b.path.length - a.path.length).find(item => item.path === '/' ? location.pathname === '/' : location.pathname.startsWith(item.path));
  return <div className={`app-shell ${customer ? 'customer-workspace' : 'internal-workspace'}`}>
    <a className="skip-link" href="#main-content">Đến nội dung chính</a>
    {menu &&
      <button className="sidebar-backdrop" aria-label="Đóng menu" onClick={() => setMenu(false)} />}
    <aside className={`sidebar ${menu ? 'sidebar-open' : ''}`}>
      <Link className="brand-link" to="/" onClick={() => setMenu(false)}>
        <Brand />
      </Link>
      <Button className="mobile-sidebar-close icon-button ghost" aria-label="Đóng menu" onClick={() => setMenu(false)}>
        <X size={22} />
      </Button>
      <div className="workspace-label">
        {customer ? 'Ngân hàng cá nhân' : 'Không gian nội bộ'} <span>DEMO</span>
      </div>
      <nav aria-label="Điều hướng chính">
        {navigationGroups.map(group =>
          <div className="navigation-group" key={group.title}>
            <span className="navigation-group-title">{group.title}</span>
            {group.items.map(item =>
              <NavLink
                key={item.path}
                to={item.path}
                end={item.path === '/' || item.path === '/fraud'}
                onClick={() => setMenu(false)}
                className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
                <item.icon size={20} strokeWidth={1.8} />
                <span>{item.title}</span>
                {item.path === '/notifications' && !!notifications?.unread &&
                  <span className="notification-nav-count" aria-hidden="true">
                    {notifications.unread > 99 ? '99+' : notifications.unread}
                  </span>}
                {item.path === '/transfer' && <ArrowUpRight className="nav-arrow" size={15} />}
              </NavLink>)}
          </div>)}
      </nav>
      <div className="sidebar-bottom">
        <div className="help-card">
          <ShieldCheck size={23} />
          <strong>Cần chúng tôi hỗ trợ?</strong>
          <p>Theo dõi tra soát và trao đổi với nhân viên ngay tại đây.</p>
          <Link to={customer ? '/support' : '/profile'} onClick={() => setMenu(false)}>
            {customer ? 'Mở trung tâm hỗ trợ' : 'Thông tin của bạn'} <ChevronRight size={14} />
          </Link>
        </div>
        <NavLink className="nav-link" to="/profile" onClick={() => setMenu(false)}><UserRound size={20} />Thông tin cá nhân</NavLink>
        <button className="nav-link logout-link" onClick={logout}><LogOut size={19} />Đăng xuất</button>
        <div className="sidebar-footnote">Dự án mô phỏng · Không giao dịch tiền thật</div>
      </div>
    </aside>
    <div className="main-shell">
      <header className="topbar">
        <div className="breadcrumb">
          <Button className="mobile-menu icon-button ghost" aria-label="Mở menu" onClick={() => setMenu(true)}>
            <Menu size={22} />
          </Button>
          <span>Ngân hàng số</span>
          <ChevronRight size={14} />
          <strong>{current?.title ?? 'Chi tiết giao dịch'}</strong>
        </div>
        <div className="topbar-right">
          <Link
            className="notification-bell"
            to="/notifications"
            aria-label={`Thông báo, ${notifications?.unread ?? 0} chưa đọc`}
            title="Xem thông báo">
            <Bell size={21} />
            {!!notifications?.unread &&
              <span className="notification-bell-count" aria-hidden="true">
                {notifications.unread > 99 ? '99+' : notifications.unread}
              </span>}
          </Link>
          <span className="secure-label"><ShieldCheck size={15} />Phiên đăng nhập cá nhân</span>
          <Link className="user-menu" to="/profile">
            <span className="avatar">{initials(name)}</span>
            <span>
              <strong>{name}</strong>
              <small>{customer ? 'Khách hàng cá nhân' : 'Tài khoản nội bộ'}</small>
            </span>
          </Link>
        </div>
      </header>
      <main id="main-content" className="page-content"><Outlet /></main>
      <footer className="app-footer">
        <span>© 2026 Agribank Portfolio · Dự án học tập</span>
        <span><CircleHelp size={14} /> Dữ liệu và giao dịch trong môi trường mô phỏng</span>
      </footer>
    </div>
    {customer && <ChatbotWidget key={me!.user.id} />}
  </div>;
}
