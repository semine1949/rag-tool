import { cn } from '@/lib/utils/cn';

export interface ProgressProps {
  /** 进度百分比 0~100 */
  value: number;
  className?: string;
  /** 进度条配色 */
  tone?: 'grad' | 'ok' | 'warn' | 'danger';
}

const TONES = {
  grad: 'bg-grad',
  ok: 'bg-ok',
  warn: 'bg-warn',
  danger: 'bg-danger',
} as const;

/** 线性进度条 */
export function Progress({ value, className, tone = 'grad' }: ProgressProps) {
  const clamped = Math.max(0, Math.min(100, value));
  return (
    <div className={cn('h-1.5 w-full overflow-hidden rounded-full bg-wash/[0.08]', className)}>
      <div
        className={cn('h-full rounded-full transition-all duration-300', TONES[tone])}
        style={{ width: `${clamped}%` }}
      />
    </div>
  );
}
