import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/utils/cn';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** 是否启用 hover 上浮辉光 */
  hoverable?: boolean;
  /** 内边距预设 */
  padding?: 'none' | 'sm' | 'md' | 'lg';
}

const PADDINGS = {
  none: '',
  sm: 'p-4',
  md: 'p-5',
  lg: 'p-6',
} as const;

/** 玻璃拟态卡片容器 */
export function Card({
  hoverable,
  padding = 'md',
  className,
  children,
  ...rest
}: CardProps) {
  return (
    <div
      className={cn('glass shadow-card', hoverable && 'glass-hover', PADDINGS[padding], className)}
      {...rest}
    >
      {children}
    </div>
  );
}

export interface CardHeaderProps {
  title: ReactNode;
  /** 副标题/说明 */
  subtitle?: ReactNode;
  /** 右侧操作区 */
  action?: ReactNode;
  className?: string;
}

/** 卡片标题区：左标题右操作 */
export function CardHeader({ title, subtitle, action, className }: CardHeaderProps) {
  return (
    <div className={cn('mb-4 flex items-start justify-between gap-4', className)}>
      <div className="min-w-0">
        <h3 className="truncate text-[15px] font-semibold text-text">{title}</h3>
        {subtitle && <p className="mt-1 text-xs text-muted">{subtitle}</p>}
      </div>
      {action && <div className="flex shrink-0 items-center gap-2">{action}</div>}
    </div>
  );
}
