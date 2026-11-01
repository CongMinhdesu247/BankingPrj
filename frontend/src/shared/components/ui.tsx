import { useEffect, useRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { AlertCircle, ArrowLeft, ArrowRight, Inbox, LoaderCircle, X } from 'lucide-react';
import { ApiFailure, errorMessage } from '../api/httpClient';
export function Button({ children, className = '', loading, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { loading?: boolean }) {
  return <button type="button" {...props} className={`button ${className}`} disabled={props.disabled || loading} aria-busy={loading || undefined}>{loading && <LoaderCircle size={17} className="spin" />}{children}</button>;
}
export function ErrorBanner({ error, retry }: { error: unknown; retry?: () => void }) {
  if (!error) return null;
  return <div className="notice error" role="alert"><AlertCircle size={20} /><div><strong>{errorMessage(error)}</strong>{error instanceof ApiFailure && error.requestId && <small>Mã tra cứu: {error.requestId}</small>}</div>{retry && <Button className="small secondary" onClick={retry}>Thử lại</Button>}</div>;
}
export function EmptyState({ title, description, action }: { title: string; description: string; action?: ReactNode }) {
  return <div className="empty-state"><span className="empty-icon"><Inbox size={27} /></span><h3>{title}</h3><p>{description}</p>{action}</div>;
}
export function Loading({ label = 'Đang tải dữ liệu…' }: { label?: string }) { return <div className="loading-state" role="status"><LoaderCircle className="spin" size={22} /><span>{label}</span></div>; }
export function StatusBadge({ status }: { status: string }) {
  const labels: Record<string, string> = { ACTIVE: 'Đang hoạt động', FROZEN: 'Tạm khóa', CLOSED: 'Đã đóng', COMPLETED: 'Thành công' };
  return <span className={`status-badge ${status.toLowerCase()}`}><i />{labels[status] ?? status}</span>;
}
export function Pagination({ page, totalPages, totalElements, onChange }: { page: number; totalPages: number; totalElements: number; onChange: (page: number) => void }) {
  if (totalElements === 0) return null;
  return <div className="pagination"><span>{totalElements} kết quả · Trang {page + 1}/{Math.max(1, totalPages)}</span><div><Button className="icon-button secondary" aria-label="Trang trước" disabled={page === 0} onClick={() => onChange(page - 1)}><ArrowLeft size={17} /></Button><Button className="icon-button secondary" aria-label="Trang sau" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}><ArrowRight size={17} /></Button></div></div>;
}
export function Modal({ title, onClose, children, busy = false }: { title: string; onClose: () => void; children: ReactNode; busy?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => dialog?.close(); }, []);
  return <dialog ref={ref} className="modal" aria-label={title} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}><div className="modal-heading"><h2>{title}</h2><Button className="icon-button ghost" aria-label="Đóng hộp thoại" onClick={onClose} disabled={busy}><X size={20} /></Button></div>{children}</dialog>;
}
export function FieldError({ children }: { children?: ReactNode }) { return children ? <span className="field-error" role="alert">{children}</span> : null; }
