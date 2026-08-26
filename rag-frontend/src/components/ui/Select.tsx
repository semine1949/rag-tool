import { forwardRef, type SelectHTMLAttributes } from 'react';
import { cn } from '@/lib/utils/cn';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  error?: string;
  hint?: string;
  options: SelectOption[];
  placeholder?: string;
}

/** 原生 select 的深色主题封装 */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { label, error, hint, options, placeholder, className, id, ...rest },
  ref,
) {
  const selectId = id || rest.name;
  return (
    <div className="w-full">
      {label && (
        <label htmlFor={selectId} className="mb-1.5 block text-xs font-medium text-muted">
          {label}
        </label>
      )}
      <div className="relative">
        <select
          ref={ref}
          id={selectId}
          className={cn(
            'h-10 w-full appearance-none rounded-xl border border-line bg-black/25 px-3.5 pr-9 text-sm text-text',
            'transition-colors focus:border-accent/60 focus:outline-none focus:ring-2 focus:ring-accent/15',
            'disabled:cursor-not-allowed disabled:opacity-50',
            error && 'border-danger/60',
            className,
          )}
          {...rest}
        >
          {placeholder && (
            <option value="" disabled className="bg-bg-2">
              {placeholder}
            </option>
          )}
          {options.map((o) => (
            <option key={o.value} value={o.value} disabled={o.disabled} className="bg-bg-2 text-text">
              {o.label}
            </option>
          ))}
        </select>
        {/* 自定义下拉箭头 */}
        <svg
          className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted"
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
        >
          <path d="M6 8l4 4 4-4" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </div>
      {error ? (
        <p className="mt-1.5 text-xs text-danger">{error}</p>
      ) : (
        hint && <p className="mt-1.5 text-xs text-muted-2">{hint}</p>
      )}
    </div>
  );
});
