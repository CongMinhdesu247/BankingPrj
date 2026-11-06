import { createContext, useContext, type ReactNode } from 'react';
import { useAuth } from '../../auth/providers/AuthProvider';
import { useLoad } from '../../../shared/hooks/useLoad';
import { accountApi } from '../api/accountApi';
import type { Account } from '../../../shared/types/api';
interface AccountsContextValue {
  accounts: Account[];
  loading: boolean;
  error: unknown;
  refresh: () => void
}
const Context = createContext<AccountsContextValue | null>(null);
export function AccountsProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const customer = me?.user.roles.includes('CUSTOMER');
  const resource = useLoad(signal => customer ? accountApi.all(signal) : Promise.resolve([]), [me?.user.id, customer]);
  return <Context.Provider value={{
    accounts: resource.data ?? [],
    loading: resource.loading,
    error: resource.error,
    refresh: resource.reload
  }}>
    {children}
  </Context.Provider>;
}
export function useAccounts() {
  const value = useContext(Context);
  if (!value) throw new Error('AccountsProvider is required');
  return value;
}
