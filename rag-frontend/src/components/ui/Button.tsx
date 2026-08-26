import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/utils/cn';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'outline';
type Size = 'sm' | 'md' | 'lg' | 'icon';

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  /** 加载态：禁用点击并显示转圈 */
  loading?: boolean;
  /** 左侧图标 */
  icon?: ReactNode;
}

/** 各变体样式：primary 使用品牌渐变 + 辉光 */
const VARIANTS: Record<Variant, string> = {
  primary:
    'bg-grad text-[#04121a] font-semibold shadow-glow hover:shadow-[0_16px_36px_-12px_rgba(34,211,238,.75)] hover:-translate-y-0.5',
  secondary:
    'bg-white/[0.06] text-text border border-line hover:bg-white/[0.1] hover:border-line-2',
  ghost: 'text-muted hover:text-text hover:bg-white/[0.06]',
  outline:
    'border border-accent/40 text-accent hover:bg-accent/10 hover:border-accent/70',
  danger:
    'bg-danger/15 text-danger border border-danger/30 hover:bg-danger/25 hover:border-danger/50',
};

const SIZES: Record<Size, string> = {
  sm: 'h-8 px-3 text-xs gap-1.5',
  md: 'h-10 px-4 text-sm gap-2',
  lg: 'h-12 px-6 text-[15px] gap-2.5',
  icon: 'h-9 w-9 text-sm',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', loading, icon, className, children, disabled, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      disabled={disabled || loading}
      className={cn(
        'inline-flex items-center justify-center rounded-xl transition-all duration-200',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/50',
        'disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:translate-y-0 disabled:hover:shadow-none',
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...rest}
    >
      {loading ? (
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent" />
      ) : (
        icon
      )}
      {children}
    </button>
  );
});
