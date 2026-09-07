import { cn } from '@/lib/utils/cn';

export interface TabItem {
  key: string;
  label: string;
  /** 右上角计数 */
  count?: number;
}

export interface TabsProps {
  items: TabItem[];
  value: string;
  onChange: (key: string) => void;
  className?: string;
}

/** 分段控制器样式的 Tabs */
export function Tabs({ items, value, onChange, className }: TabsProps) {
  return (
    <div
      className={cn(
        'inline-flex items-center gap-1 rounded-xl border border-line bg-field p-1',
        className,
      )}
    >
      {items.map((it) => {
        const active = it.key === value;
        return (
          <button
            key={it.key}
            onClick={() => onChange(it.key)}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-lg px-3.5 py-1.5 text-xs font-medium transition-all',
              active
                ? 'bg-grad text-onaccent shadow-glow'
                : 'text-muted hover:bg-wash/[0.06] hover:text-text',
            )}
          >
            {it.label}
            {it.count !== undefined && (
              <span
                className={cn(
                  'rounded-full px-1.5 text-[10px]',
                  active ? 'bg-black/20' : 'bg-wash/[0.08]',
                )}
              >
                {it.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
