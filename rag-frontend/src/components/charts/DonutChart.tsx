import { useState } from 'react';
import type { DocTypeSlice } from '@/lib/types';
import { formatNumber } from '@/lib/utils/format';

export interface DonutChartProps {
  data: DocTypeSlice[];
  size?: number;
  thickness?: number;
}

/**
 * 文档类型分布环形图（SVG）
 * 每个扇区独立描边，hover 高亮并在中心显示占比
 */
export function DonutChart({ data, size = 168, thickness = 22 }: DonutChartProps) {
  const [active, setActive] = useState<number | null>(null);

  const total = data.reduce((sum, d) => sum + d.count, 0) || 1;
  const radius = (size - thickness) / 2;
  const circumference = 2 * Math.PI * radius;

  // 依次累加偏移量，拼出各扇区
  let offset = 0;
  const arcs = data.map((d, i) => {
    const ratio = d.count / total;
    const dash = ratio * circumference;
    const arc = { ...d, dash, offset, ratio, index: i };
    offset += dash;
    return arc;
  });

  const activeSlice = active !== null ? arcs[active] : null;

  return (
    <div className="flex items-center gap-6">
      <div
        className="relative shrink-0"
        style={{ width: size, height: size }}
        onMouseLeave={() => setActive(null)}
      >
        <svg width={size} height={size} className="-rotate-90">
          {arcs.map((a) => (
            <circle
              key={a.type}
              cx={size / 2}
              cy={size / 2}
              r={radius}
              fill="none"
              stroke={a.color}
              strokeWidth={active === a.index ? thickness + 5 : thickness}
              strokeDasharray={`${a.dash} ${circumference - a.dash}`}
              strokeDashoffset={-a.offset}
              className="cursor-pointer transition-all duration-200"
              opacity={active === null || active === a.index ? 1 : 0.35}
              onMouseEnter={() => setActive(a.index)}
            />
          ))}
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          {activeSlice ? (
            <>
              <span className="text-xl font-bold text-text">
                {(activeSlice.ratio * 100).toFixed(1)}%
              </span>
              <span className="mt-0.5 text-[11px] text-muted">{activeSlice.type}</span>
            </>
          ) : (
            <>
              <span className="text-xl font-bold text-text">{formatNumber(total)}</span>
              <span className="mt-0.5 text-[11px] text-muted">文档总量</span>
            </>
          )}
        </div>
      </div>

      {/* 图例列表 */}
      <div className="min-w-0 flex-1 space-y-2.5">
        {arcs.map((a) => (
          <div
            key={a.type}
            onMouseEnter={() => setActive(a.index)}
            onMouseLeave={() => setActive(null)}
            className="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-2 py-1 transition-colors hover:bg-white/[0.05]"
          >
            <span className="flex min-w-0 items-center gap-2 text-xs text-muted">
              <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: a.color }} />
              <span className="truncate">{a.type}</span>
            </span>
            <span className="shrink-0 text-xs font-medium text-text">
              {formatNumber(a.count)}
              <span className="ml-1.5 text-muted-2">{(a.ratio * 100).toFixed(0)}%</span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
