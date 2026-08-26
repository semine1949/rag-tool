import type { ReactNode } from 'react';
import { cn } from '@/lib/utils/cn';

export type BadgeTone =
  | 'accent'
  | 'accent2'
  | 'accent3'
  | 'ok'
  | 'warn'
  | 'danger'
  | 'neutral';

const TONES: Record<BadgeTone, string> = {
  accent: 'bg-accent/12 text-accent border-accent/25',
  accent2: 'bg-accent-2/12 text-accent-2 border-accent-2/25',
  accent3: 'bg-accent-3/14 text-accent-3 border-accent-3/28',
  ok: 'bg-ok/12 text-ok border-ok/25',
  warn: 'bg-warn/12 text-warn border-warn/25',
  danger: 'bg-danger/12 text-danger border-danger/25',
  neutral: 'bg-white/[0.06] text-muted border-line',
};

export interface BadgeProps {
  tone?: BadgeTone;
  children: ReactNode;
  /** 是否显示前置圆点 */
  dot?: boolean;
  className?: string;
}

/** 状态标签 */
export function Badge({ tone = 'neutral', dot, children, className }: BadgeProps) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[11px] font-medium leading-5',
        TONES[tone],
        className,
      )}
    >
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}
