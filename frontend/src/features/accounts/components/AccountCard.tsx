import { ArrowUpRight, CreditCard, Eye, EyeOff } from 'lucide-react';
import { Link } from 'react-router';
import { accountNumber, money } from '../../../shared/lib/format';
import type { Account } from '../../../shared/types/api';
import { Button, StatusBadge } from '../../../shared/components/ui';
export function AccountCard({ account, featured = false, hidden = false, onToggle }: { account: Account; featured?: boolean; hidden?: boolean; onToggle?: () => void }) {
  return <article className={`account-card ${featured ? 'featured' : ''}`}><div className="account-card-top"><span className="card-chip"><CreditCard size={22} /></span><span>Tài khoản thanh toán</span><StatusBadge status={account.status} /></div><div className="card-number">{accountNumber(account.accountNumber)}</div><div className="account-card-bottom"><div><span>Số dư hiện tại</span><strong>{hidden ? '••••••••' : money(account.balance)}</strong></div>{onToggle ? <Button className="icon-button ghost" aria-label={hidden ? 'Hiện số dư' : 'Ẩn số dư'} onClick={onToggle}>{hidden ? <EyeOff size={19} /> : <Eye size={19} />}</Button> : <Link className="card-link" to={`/accounts/${account.id}`} aria-label={`Chi tiết tài khoản ${account.accountNumber}`}><ArrowUpRight size={23} /></Link>}</div></article>;
}
