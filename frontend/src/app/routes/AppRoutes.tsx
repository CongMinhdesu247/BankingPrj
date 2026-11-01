import { Route, Routes, Link, Navigate } from 'react-router';
import { useAuth } from '../../features/auth/providers/AuthProvider';
import { FraudDashboardPage } from '../../features/fraud/pages/FraudDashboardPage';
import { FraudAlertsPage } from '../../features/fraud/pages/FraudAlertsPage';
import { FraudInvestigationPage } from '../../features/fraud/pages/FraudInvestigationPage';
import { FraudRulesPage } from '../../features/fraud/pages/FraudRulesPage';
import { NotificationsPage } from '../../features/notifications/pages/NotificationsPage';
import { StaffCustomersPage, StaffCustomerPage, StaffAccountPage, StaffTransactionPage } from '../../features/staff/pages/StaffInvestigationPages';
import { AccountsProvider } from '../../features/accounts/providers/AccountsProvider';
import { AppLayout } from '../layout/AppLayout';
import { EmptyState } from '../../shared/components/ui';
import { AccountDetailPage } from '../../features/accounts/pages/AccountDetailPage';
import { AccountsPage } from '../../features/accounts/pages/AccountsPage';
import { BeneficiariesPage } from '../../features/beneficiaries/pages/BeneficiariesPage';
import { DashboardPage } from '../../features/dashboard/pages/DashboardPage';
import { LoginPage } from '../../features/auth/pages/LoginPage';
import { ProfilePage } from '../../features/profile/pages/ProfilePage';
import { TransactionHistoryPage } from '../../features/transfers/pages/TransactionHistoryPage';
import { TransferPage } from '../../features/transfers/pages/TransferPage';
import { TransferResultPage } from '../../features/transfers/pages/TransferResultPage';
import { ProtectedRoute } from './ProtectedRoute';
import { StatementPage } from '../../features/accounts/pages/StatementPage';
import { SecurityPage } from '../../features/security/pages/SecurityPage';
import { AccountRiskPage } from '../../features/fraud/pages/AccountRiskPage';
import { SecurityCenterPage } from '../../features/security/pages/SecurityCenterPage';
import { PasswordRecoveryPage } from '../../features/auth/pages/PasswordRecoveryPage';
import { SupportPage, SupportDetailPage } from '../../features/support/pages/SupportPages';
import { TransferTemplatesPage } from '../../features/transfers/pages/TransferTemplatesPage';
import { PersonalLimitsPage, AdminLimitRequestsPage } from '../../features/limits/pages/PersonalLimitsPage';
import { NotificationsProvider } from '../../features/notifications/providers/NotificationsProvider';
function Layout() { return <AccountsProvider><NotificationsProvider><AppLayout /></NotificationsProvider></AccountsProvider>; }
function Home() {
  const { me } = useAuth(); const roles = me!.user.roles;
  if (roles.includes('RISK_OFFICER')) return <Navigate to="/fraud" replace />;
  if (roles.includes('ADMIN')) return <Navigate to="/staff/account-risk" replace />;
  if (roles.includes('BANK_STAFF')) return <Navigate to="/staff/customers" replace />;
  return <DashboardPage />;
}
export function AppRoutes() {
  return <Routes><Route path="/forgot-password" element={<PasswordRecoveryPage />} /><Route path="/reset-password" element={<PasswordRecoveryPage />} /><Route path="/login" element={<LoginPage />} /><Route element={<ProtectedRoute />}><Route element={<Layout />}><Route index element={<Home />} /><Route path="profile" element={<ProfilePage />} /><Route path="security" element={<SecurityCenterPage />} /><Route path="security/history" element={<SecurityPage />} /><Route path="notifications" element={<NotificationsPage />} /><Route element={<ProtectedRoute customerOnly />}><Route path="templates" element={<TransferTemplatesPage />} /><Route path="limits" element={<PersonalLimitsPage />} /><Route path="support" element={<SupportPage />} /><Route path="support/:id" element={<SupportDetailPage />} /><Route path="statement" element={<StatementPage />} /><Route path="accounts" element={<AccountsPage />} /><Route path="accounts/:id" element={<AccountDetailPage />} /><Route path="transfer" element={<TransferPage />} /><Route path="transactions/:id" element={<TransferResultPage />} /><Route path="history" element={<TransactionHistoryPage />} /><Route path="beneficiaries" element={<BeneficiariesPage />} /></Route><Route element={<ProtectedRoute roles={['RISK_OFFICER']} />}><Route path="fraud" element={<FraudDashboardPage />} /><Route path="fraud/alerts" element={<FraudAlertsPage />} /><Route path="fraud/alerts/:id" element={<FraudInvestigationPage />} /></Route><Route element={<ProtectedRoute roles={['ADMIN', 'RISK_OFFICER']} />}><Route path="staff/account-risk" element={<AccountRiskPage />} /></Route><Route element={<ProtectedRoute roles={['ADMIN']} />}><Route path="staff/limit-requests" element={<AdminLimitRequestsPage />} /><Route path="fraud/rules" element={<FraudRulesPage />} /></Route><Route element={<ProtectedRoute roles={['BANK_STAFF', 'RISK_OFFICER', 'ADMIN']} />}><Route element={<ProtectedRoute roles={['BANK_STAFF', 'ADMIN']} />}><Route path="staff/support" element={<SupportPage />} /><Route path="staff/support/:id" element={<SupportDetailPage />} /></Route><Route path="staff/customers" element={<StaffCustomersPage />} /><Route path="staff/customers/:id" element={<StaffCustomerPage />} /><Route path="staff/accounts/:id" element={<StaffAccountPage />} /><Route path="staff/transactions/:id" element={<StaffTransactionPage />} /></Route><Route path="*" element={<div className="panel"><EmptyState title="Không tìm thấy trang" description="Trang bạn tìm có thể đã thay đổi hoặc không tồn tại." action={<Link to="/" className="button primary">Về tổng quan</Link>} /></div>} /></Route></Route></Routes>;
}
