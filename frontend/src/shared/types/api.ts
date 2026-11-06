export type Role = 'CUSTOMER' | 'BANK_STAFF' | 'RISK_OFFICER' | 'ADMIN';
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED';
export interface User {
  id: string;
  username: string;
  enabled: boolean;
  customerId: string | null;
  roles: Role[];
  createdAt: string;
  updatedAt: string
}
export interface Customer {
  id: string;
  customerCode: string;
  fullName: string;
  email: string | null;
  phone: string | null;
  status: string;
  createdAt: string;
  updatedAt: string
}
export interface Me { user: User; customer: Customer | null }
export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: User
}
export interface Account {
  id: string;
  customerId: string;
  accountNumber: string;
  currency: 'VND';
  balance: string;
  status: AccountStatus;
  createdAt: string;
  updatedAt: string
}
export interface Recipient {
  id: string;
  accountNumber: string;
  currency: 'VND';
  status: AccountStatus
}
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean
}
export interface Transaction {
  id: string;
  reference: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  currency: 'VND';
  type: 'INTERNAL_TRANSFER' | 'OPENING';
  status: string;
  description: string | null;
  createdAt: string
}
export interface Entry {
  id: string;
  accountId: string;
  direction: 'DEBIT' | 'CREDIT';
  amount: string;
  currency: 'VND';
  balanceAfter: string | null
}
export interface TransactionDetail { transaction: Transaction; entries: Entry[] }
export interface TransferPayload {
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  currency: 'VND';
  description: string | null
}
export interface Beneficiary {
  id: string;
  destinationAccountId: string;
  destinationAccountNumber: string;
  nickname: string | null;
  createdAt: string
}
export interface TransferLimit {
  accountId: string;
  perTransferAmount: string;
  dailyAmount: string;
  dailyUsed: string;
  dailyRemaining: string;
  currency: 'VND';
  date: string;
  timezone: string;
  defaultApplied: boolean
}
export interface ErrorBody {
  code: string;
  message: string;
  fieldErrors: { field: string; message: string }[];
  requestId: string;
  timestamp: string;
  path: string
}
