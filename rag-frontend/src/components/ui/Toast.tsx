import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import { cn } from '@/lib/utils/cn';

type ToastType = 'success' | 'error' | 'info' | 'warning';

interface ToastItem {
  id: number;
  type: ToastType;
  message: string;
}

interface ToastContextValue {
  success: (msg: string) => void;
  error: (msg: string) => void;
  info: (msg: string) => void;
  warning: (msg: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

/** 各类型的配色与图标 */
const TOAST_STYLE: Record<ToastType, { cls: string; icon: ReactNode }> = {
  success: {
    cls: 'border-ok/35 text-ok',
    icon: (
      <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M4 10.5l4 4 8-9" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  },
  error: {
    cls: 'border-danger/35 text-danger',
    icon: (
      <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M5 5l10 10M15 5L5 15" strokeLinecap="round" />
      </svg>
    ),
  },
  warning: {
    cls: 'border-warn/35 text-warn',
    icon: (
      <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M10 6v5M10 14h.01" strokeLinecap="round" />
      </svg>
    ),
  },
  info: {
    cls: 'border-accent/35 text-accent',
    icon: (
      <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2">
        <path d="M10 9v5M10 6h.01" strokeLinecap="round" />
      </svg>
    ),
  },
};

let toastSeq = 0;

/** Toast 全局提供者，需包裹在应用根部 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);

  const push = useCallback((type: ToastType, message: string) => {
    const id = ++toastSeq;
    setItems((prev) => [...prev, { id, type, message }]);
    // 3.2s 后自动移除
    window.setTimeout(() => {
      setItems((prev) => prev.filter((t) => t.id !== id));
    }, 3200);
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({
      success: (m) => push('success', m),
      error: (m) => push('error', m),
      info: (m) => push('info', m),
      warning: (m) => push('warning', m),
    }),
    [push],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      {createPortal(
        <div className="pointer-events-none fixed right-5 top-5 z-[60] flex w-[340px] flex-col gap-2.5">
          {items.map((t) => (
            <div
              key={t.id}
              className={cn(
                'glass pointer-events-auto flex animate-fade items-start gap-2.5 px-4 py-3 shadow-card',
                TOAST_STYLE[t.type].cls,
              )}
            >
              <span className="mt-0.5 shrink-0">{TOAST_STYLE[t.type].icon}</span>
              <p className="text-[13px] leading-relaxed text-text">{t.message}</p>
            </div>
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
}

/** 获取 Toast 触发方法 */
export function useToast(): ToastContextValue {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast 必须在 ToastProvider 内使用');
  return ctx;
}
