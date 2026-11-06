import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Bell, X } from 'lucide-react';
import { useAuth } from '../../auth/providers/AuthProvider';
import { notificationApi, type Notification } from '../api/notificationApi';
import { isSupportNotification, supportNotificationDescription, supportNotificationTarget, supportNotificationTitle } from '../lib/notificationPresentation';
interface NotificationContextValue {
  unread: number;
  latestId: string | null;
  refresh: () => void
}
const Context = createContext<NotificationContextValue | null>(null);
export function useNotifications() { return useContext(Context); }
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const { me } = useAuth();
  const [summary, setSummary] = useState({ unread: 0, latestId: null as string | null });
  const [toast, setToast] = useState<Notification | null>(null);
  const refreshRef = useRef<() => void>(() => { });
  const refresh = useCallback(() => refreshRef.current(), []);
  useEffect(() => {
    if (!me) return;
    let active = true, busy = false, pendingRefresh = false, initialized = false;
    let latestId: string | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;
    setSummary({ unread: 0, latestId: null });
    setToast(null);
    const isHidden = () => document.visibilityState === 'hidden';
    async function poll() {
      if (!active) return;
      if (busy) {
        pendingRefresh = true;
        return;
      }
      if (timer) clearTimeout(timer);
      if (isHidden()) return;
      busy = true;
      pendingRefresh = false;
      controller = new AbortController();
      try {
        const data = await notificationApi.summary(controller.signal);
        if (!active) return;
        const nextId = data.latest?.id ?? null;
        if (initialized && nextId && nextId !== latestId && data.latest?.status === 'UNREAD' && isSupportNotification(data.latest)) setToast(data.latest);
        initialized = true;
        latestId = nextId;
        setSummary(previous => previous.unread === data.unreadCount && previous.latestId === nextId
          ? previous
          : { unread: data.unreadCount, latestId: nextId });
      }
      catch { /* Preserve the last known count; retry while the page is active. */ }
      finally {
        busy = false;
        if (active) {
          if (pendingRefresh) void poll();
          else if (!isHidden()) timer = setTimeout(() => void poll(), 10000);
        }
      }
    }
    const resume = () => { if (document.visibilityState !== 'hidden') void poll(); };
    refreshRef.current = () => void poll();
    void poll();
    window.addEventListener('focus', resume);
    document.addEventListener('visibilitychange', resume);
    return () => {
      active = false;
      controller?.abort();
      if (timer) clearTimeout(timer);
      refreshRef.current = () => { };
      window.removeEventListener('focus', resume);
      document.removeEventListener('visibilitychange', resume);
    };
  }, [me?.user.id]);
  function openToast(item: Notification) {
    setToast(null);
    if (item.status === 'UNREAD') void notificationApi.read(item.id).then(refresh).catch(() => { /* The inbox retains the unread item if marking it fails. */ });
  }
  return <Context.Provider value={{ ...summary, refresh }}>
    {children}
    {toast &&
      <aside className="support-notification-toast" aria-label="Thông báo tra soát mới">
        <Bell size={20} />
        <div>
          <div role="status" aria-live="polite">
            <strong>{supportNotificationTitle(toast)}</strong>
            <p>{supportNotificationDescription(toast)}</p>
            {toast.content.messagePreview &&
              <p className="notification-preview">{toast.content.messagePreview}</p>}
          </div>
          <Link className="text-link" to={supportNotificationTarget(toast)} onClick={() => openToast(toast)}>Mở trao đổi với CSKH</Link>
        </div>
        <button
          className="icon-button notification-toast-close"
          type="button"
          aria-label="Đóng thông báo tra soát"
          onClick={() => setToast(null)}>
          <X size={17} />
        </button>
      </aside>}
  </Context.Provider>;
}
