import type { ReactNode } from 'react';
import { cn } from '@/lib/utils/cn';

export interface CheckboxProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label?: ReactNode;
  disabled?: boolean;
  className?: string;
}

/** 自定义复选框，选中态使用品牌渐变 */
export function Checkbox({ checked, onChange, label, disabled, className }: CheckboxProps) {
  return (
    <label
      className={cn(
        'inline-flex cursor-pointer select-none items-center gap-2.5',
        disabled && 'cursor-not-allowed opacity-50',
        className,
      )}
    >
      <span className="relative flex h-4 w-4 shrink-0 items-center justify-center">
        <input
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="peer sr-only"
        />
        <span
          className={cn(
            'h-4 w-4 rounded-[5px] border transition-all',
            checked ? 'border-transparent bg-grad' : 'border-line-2 bg-black/30',
          )}
        />
        {checked && (
          <svg
            className="absolute h-3 w-3 text-[#04121a]"
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="3"
          >
            <path d="M4 10.5l4 4 8-9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        )}
      </span>
      {label && <span className="text-sm text-text">{label}</span>}
    </label>
  );
}

export interface SwitchProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label?: ReactNode;
  disabled?: boolean;
}

/** 开关组件 */
export function Switch({ checked, onChange, label, disabled }: SwitchProps) {
  return (
    <label
      className={cn(
        'inline-flex cursor-pointer select-none items-center gap-2.5',
        disabled && 'cursor-not-allowed opacity-50',
      )}
    >
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={cn(
          'relative h-5 w-9 rounded-full border transition-all',
          checked ? 'border-transparent bg-grad' : 'border-line-2 bg-black/40',
        )}
      >
        <span
          className={cn(
            'absolute top-0.5 h-3.5 w-3.5 rounded-full bg-white transition-all',
            checked ? 'left-[18px]' : 'left-0.5',
          )}
        />
      </button>
      {label && <span className="text-sm text-text">{label}</span>}
    </label>
  );
}
