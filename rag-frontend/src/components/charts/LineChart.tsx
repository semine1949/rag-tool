import { useMemo, useState } from 'react';
import type { TrendPoint } from '@/lib/types';

export interface LineSeries {
  name: string;
  points: TrendPoint[];
  color: string;
  /** 是否填充渐变面积 */
  area?: boolean;
  /** 是否虚线（用于对比上期） */
  dashed?: boolean;
}

export interface LineChartProps {
  series: LineSeries[];
  height?: number;
  /** Y 轴刻度数量 */
  yTicks?: number;
}

/**
 * 纯 SVG 折线/面积图
 * 支持多序列、网格、悬浮游标与 tooltip，无第三方图表依赖
 */
export function LineChart({ series, height = 240, yTicks = 4 }: LineChartProps) {
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);

  // 视图坐标系（viewBox 内部单位），实际尺寸由 CSS 拉伸
  const W = 720;
  const H = height;
  const PAD = { top: 16, right: 12, bottom: 28, left: 44 };

  const { maxY, xCount } = useMemo(() => {
    const all = series.flatMap((s) => s.points.map((p) => p.value));
    const max = Math.max(...all, 1);
    // 向上取整到较美观的刻度
    const step = Math.pow(10, Math.floor(Math.log10(max)));
    const nice = Math.ceil(max / step) * step;
    return { maxY: nice, xCount: series[0]?.points.length ?? 0 };
  }, [series]);

  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;

  /** 索引 -> X 坐标 */
  const xOf = (i: number) => PAD.left + (xCount <= 1 ? innerW / 2 : (i / (xCount - 1)) * innerW);
  /** 数值 -> Y 坐标 */
  const yOf = (v: number) => PAD.top + innerH - (v / maxY) * innerH;

  /** 生成平滑折线路径（Catmull-Rom 转贝塞尔） */
  const linePath = (points: TrendPoint[]): string => {
    if (points.length === 0) return '';
    const pts = points.map((p, i) => ({ x: xOf(i), y: yOf(p.value) }));
    if (pts.length < 3) {
      return pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x},${p.y}`).join(' ');
    }
    let d = `M${pts[0].x},${pts[0].y}`;
    for (let i = 0; i < pts.length - 1; i++) {
      const p0 = pts[i === 0 ? 0 : i - 1];
      const p1 = pts[i];
      const p2 = pts[i + 1];
      const p3 = pts[i + 2] ?? p2;
      // 张力系数 6 让曲线更贴合数据点
      const c1x = p1.x + (p2.x - p0.x) / 6;
      const c1y = p1.y + (p2.y - p0.y) / 6;
      const c2x = p2.x - (p3.x - p1.x) / 6;
      const c2y = p2.y - (p3.y - p1.y) / 6;
      d += ` C${c1x},${c1y} ${c2x},${c2y} ${p2.x},${p2.y}`;
    }
    return d;
  };

  const areaPath = (points: TrendPoint[]): string => {
    const line = linePath(points);
    if (!line) return '';
    return `${line} L${xOf(points.length - 1)},${PAD.top + innerH} L${xOf(0)},${
      PAD.top + innerH
    } Z`;
  };

  const labels = series[0]?.points.map((p) => p.label) ?? [];

  return (
    <div className="relative w-full">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="w-full"
        style={{ height }}
        onMouseLeave={() => setHoverIdx(null)}
      >
        <defs>
          {series.map((s, i) => (
            <linearGradient key={s.name} id={`line-area-${i}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={s.color} stopOpacity="0.32" />
              <stop offset="100%" stopColor={s.color} stopOpacity="0" />
            </linearGradient>
          ))}
        </defs>

        {/* 横向网格与 Y 轴刻度 */}
        {Array.from({ length: yTicks + 1 }).map((_, i) => {
          const v = (maxY / yTicks) * i;
          const y = yOf(v);
          return (
            <g key={i}>
              <line
                x1={PAD.left}
                y1={y}
                x2={W - PAD.right}
                y2={y}
                stroke="rgba(255,255,255,0.06)"
                strokeWidth="1"
              />
              <text x={PAD.left - 10} y={y + 4} textAnchor="end" fontSize="10" fill="#5f6e8c">
                {v >= 1000 ? `${(v / 1000).toFixed(1)}k` : v}
              </text>
            </g>
          );
        })}

        {/* X 轴标签：稀疏显示避免重叠 */}
        {labels.map((l, i) =>
          i % 2 === 0 ? (
            <text
              key={l}
              x={xOf(i)}
              y={H - 8}
              textAnchor="middle"
              fontSize="10"
              fill="#5f6e8c"
            >
              {l}
            </text>
          ) : null,
        )}

        {/* 数据序列 */}
        {series.map((s, i) => (
          <g key={s.name}>
            {s.area && <path d={areaPath(s.points)} fill={`url(#line-area-${i})`} />}
            <path
              d={linePath(s.points)}
              fill="none"
              stroke={s.color}
              strokeWidth="2.2"
              strokeDasharray={s.dashed ? '5 5' : undefined}
              strokeLinecap="round"
              opacity={s.dashed ? 0.55 : 1}
            />
          </g>
        ))}

        {/* 悬浮游标线与数据点 */}
        {hoverIdx !== null && (
          <g>
            <line
              x1={xOf(hoverIdx)}
              y1={PAD.top}
              x2={xOf(hoverIdx)}
              y2={PAD.top + innerH}
              stroke="rgba(255,255,255,0.22)"
              strokeWidth="1"
            />
            {series.map((s) => {
              const p = s.points[hoverIdx];
              if (!p) return null;
              return (
                <circle
                  key={s.name}
                  cx={xOf(hoverIdx)}
                  cy={yOf(p.value)}
                  r="4"
                  fill="#070b15"
                  stroke={s.color}
                  strokeWidth="2.2"
                />
              );
            })}
          </g>
        )}

        {/* 透明热区，捕获鼠标位置 */}
        {labels.map((_, i) => (
          <rect
            key={i}
            x={xOf(i) - innerW / (xCount * 2)}
            y={PAD.top}
            width={innerW / xCount}
            height={innerH}
            fill="transparent"
            onMouseEnter={() => setHoverIdx(i)}
          />
        ))}
      </svg>

      {/* Tooltip */}
      {hoverIdx !== null && (
        <div
          className="glass pointer-events-none absolute z-10 min-w-[132px] px-3 py-2 text-xs shadow-card"
          style={{
            left: `calc(${(xOf(hoverIdx) / W) * 100}% - 66px)`,
            top: 4,
          }}
        >
          <p className="mb-1.5 font-medium text-text">{labels[hoverIdx]}</p>
          {series.map((s) => (
            <div key={s.name} className="flex items-center justify-between gap-3">
              <span className="flex items-center gap-1.5 text-muted">
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: s.color }} />
                {s.name}
              </span>
              <span className="font-medium text-text">{s.points[hoverIdx]?.value ?? '-'}</span>
            </div>
          ))}
        </div>
      )}

      {/* 图例 */}
      <div className="mt-2 flex items-center justify-center gap-5">
        {series.map((s) => (
          <span key={s.name} className="flex items-center gap-1.5 text-xs text-muted">
            <span
              className="h-0.5 w-4 rounded-full"
              style={{
                background: s.color,
                opacity: s.dashed ? 0.55 : 1,
              }}
            />
            {s.name}
          </span>
        ))}
      </div>
    </div>
  );
}
