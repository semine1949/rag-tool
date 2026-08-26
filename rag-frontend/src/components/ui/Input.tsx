import { forwardRef, type InputHTMLAttributes, type ReactNode, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/utils/cn';

const FIELD_BASE =
  'w-full rounded-xl bg-black/25 border border-line px-3.5 text-sm text-text placeholder:text-muted-2 ' +
  'transition-colors focus:outline-none focus:border-accent/60 focus:ring-2 focus:ring-accent/15 ' +
  'disabled:opacity-50 disabled:cursor-not-allowed';

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  /** 校验错误提示 */
  error?: string;
  hint?: string;
  /** 左侧装饰图标 */
  prefixIcon?: ReactNode;
  /** 右侧装饰/操作 */
  suffix?: ReactNode;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, error, hint, prefixIcon, suffix, className, id, ...rest },
  ref,
) {
  const inputId = id || rest.name;
  return (
    <div className="w-full">
      {label && (
        <label htmlFor={inputId} className="mb-1.5 block text-xs font-medium text-muted">
          {label}
        </label>
      )}
      <div className="relative">
        {prefixIcon && (
          <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted">
            {prefixIcon}
          </span>
        )}
        <input
          ref={ref}
          id={inputId}
          className={cn(
            FIELD_BASE,
            'h-10',
            prefixIcon && 'pl-9',
            suffix && 'pr-10',
            error && 'border-danger/60 focus:border-danger focus:ring-danger/15',
            className,
          )}
          {...rest}
        />
        {suffix && (
          <span className="absolute right-3 top-1/2 -translate-y-1/2 text-muted">{suffix}</span>
        )}
      </div>
      {error ? (
        <p className="mt-1.5 text-xs text-danger">{error}</p>
      ) : (
        hint && <p className="mt-1.5 text-xs text-muted-2">{hint}</p>
      )}
    </div>
  );
});

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  label?: string;
  error?: string;
  hint?: string;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { label, error, hint, className, id, ...rest },
  ref,
) {
  const areaId = id || rest.name;
  return (
    <div className="w-full">
      {label && (
        <label htmlFor={areaId} className="mb-1.5 block text-xs font-medium text-muted">
          {label}
        </label>
      )}
      <textarea
        ref={ref}
        id={areaId}
        className={cn(
          FIELD_BASE,
          'py-2.5 resize-none leading-relaxed',
          error && 'border-danger/60 focus:border-danger focus:ring-danger/15',
          className,
        )}
        {...rest}
      />
      {error ? (
        <p className="mt-1.5 text-xs text-danger">{error}</p>
      ) : (
        hint && <p className="mt-1.5 text-xs text-muted-2">{hint}</p>
      )}
    </div>
  );
});
