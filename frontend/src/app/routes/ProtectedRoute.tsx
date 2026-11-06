import { Navigate, Outlet, useLocation } from 'react-router';
import { useAuth } from '../../features/auth/providers/AuthProvider';
import type { Role } from '../../shared/types/api';
export function ProtectedRoute({ customerOnly = false, roles }: { customerOnly?: boolean; roles?: Role[] }) {
  const { me } = useAuth();
  const location = useLocation();
  if (!me) return <Navigate to="/login" state={{ from: location.pathname + location.search }} replace />;
  if (customerOnly && !me.user.roles.includes('CUSTOMER')) return <Navigate to="/" replace />;
  if (roles && !roles.some(role => me.user.roles.includes(role))) return <Navigate to="/" replace />;
  return <Outlet />;
}
