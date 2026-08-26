import type { ReactNode } from 'react';
import { cn } from '@/lib/utils/cn';

/** 列定义 */
export interface Column<T> {
  key: string;
  title: ReactNode;
  /** 单元格渲染函数 */
  render: (row: T, index: number) => ReactNode;
  width?: string;
  align?: 'left' | 'center' | 'right';
  className?: string;
}

export interface TableProps<T> {
  columns: Column<T>[];
  data: T[];
  rowKey: (row: T) => string | number;
  loading?: boolean;
  /** 空数据文案 */
  emptyText?: string;
  onRowClick?: (row: T) => void;
  className?: string;
}

const ALIGN = {
  left: 'text-left',
  center: 'text-center',
  right: 'text-right',
} as const;

/** 通用表格：深色玻璃风格 + 骨架屏 + 空态 */
export function Table<T>({
  columns,
  data,
  rowKey,
  loading,
  emptyText = '暂无数据',
  onRowClick,
  className,
}: TableProps<T>) {
  return (
    <div className={cn('overflow-x-auto', className)}>
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b border-line">
            {columns.map((c) => (
              <th
                key={c.key}
                style={{ width: c.width }}
                className={cn(
                  'whitespace-nowrap px-4 py-3 text-xs font-medium uppercase tracking-wide text-muted',
                  ALIGN[c.align ?? 'left'],
                )}
              >
                {c.title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading ? (
            // 骨架屏：3 行占位
            Array.from({ length: 3 }).map((_, i) => (
              <tr key={`skeleton-${i}`} className="border-b border-line/60">
                {columns.map((c) => (
                  <td key={c.key} className="px-4 py-4">
                    <div className="h-3.5 w-full max-w-[160px] animate-pulse rounded bg-white/[0.07]" />
                  </td>
                ))}
              </tr>
            ))
          ) : data.length === 0 ? (
            <tr>
              <td colSpan={columns.length} className="px-4 py-14 text-center text-sm text-muted">
                {emptyText}
              </td>
            </tr>
          ) : (
            data.map((row, i) => (
              <tr
                key={rowKey(row)}
                onClick={() => onRowClick?.(row)}
                className={cn(
                  'border-b border-line/60 transition-colors last:border-0 hover:bg-white/[0.035]',
                  onRowClick && 'cursor-pointer',
                )}
              >
                {columns.map((c) => (
                  <td
                    key={c.key}
                    className={cn('px-4 py-3.5 align-middle', ALIGN[c.align ?? 'left'], c.className)}
                  >
                    {c.render(row, i)}
                  </td>
                ))}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
