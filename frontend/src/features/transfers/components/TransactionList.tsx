import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight, ChevronRight } from 'lucide-react';
import { Link } from 'react-router';
import { dateTime, money } from '../../../shared/lib/format';
import type { Transaction } from '../../../shared/types/api';
import { StatusBadge } from '../../../shared/components/ui';
export function TransactionList({ transactions, ownedIds }: { transactions: Transaction[]; ownedIds: Set<string> }) {
  return <div className="transaction-list">
    <div className="transaction-list-heading">
      <span>Giao dịch</span>
      <span>Thời gian</span>
      <span>Số tiền</span>
      <span>Trạng thái</span>
      <span />
    </div>
    {transactions.map(transaction => {
      const outgoing = ownedIds.has(transaction.sourceAccountId),
        incoming = ownedIds.has(transaction.destinationAccountId);
      const internal = outgoing && incoming;
      const kind = internal ? 'internal' : outgoing ? 'outgoing' : 'incoming';
      const Icon = internal ? ArrowLeftRight : outgoing ? ArrowUpRight : ArrowDownLeft;
      return <Link key={transaction.id} className="transaction-row" to={`/transactions/${transaction.id}`}>
        <div className="transaction-description">
          <span className={`transaction-icon ${kind}`}><Icon size={20} /></span>
          <div>
            <strong>
              {transaction.description || (transaction.type === 'OPENING'
                ? 'Cấp tiền mở sổ'
                : internal ? 'Chuyển giữa tài khoản của bạn' : outgoing ? 'Chuyển tiền nội bộ' : 'Nhận tiền nội bộ')}
            </strong>
            <small>{transaction.reference.slice(0, 17)}…</small>
          </div>
        </div>
        <span className="transaction-time">{dateTime(transaction.createdAt)}</span>
        <strong className={`transaction-amount ${kind}`}>
          {internal ? '' : outgoing ? '− ' : '+ '}
          {money(transaction.amount)}
        </strong>
        <StatusBadge status={transaction.status} />
        <ChevronRight className="row-chevron" size={17} />
      </Link>;
    })}
  </div>;
}
