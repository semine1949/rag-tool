export interface RingChartProps {
  /** 百分比 0~100 */
  value: number;
  size?: number;
  /** 环宽 */
  thickness?: number;
  label?: string;
  /** 中心副标题 */
  caption?: string;
}

/**
 * 健康度环形进度图（SVG）
 * 使用 strokeDasharray 控制进度，渐变描边呼应主色
 */
export function RingChart({
  value,
  size = 168,
  thickness = 12,
  label,
  caption,
}: RingChartProps) {
  const clamped = Math.max(0, Math.min(100, value));
  const radius = (size - thickness) / 2;
  const circumference = 2 * Math.PI * radius;
  const dash = (clamped / 100) * circumference;

  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg width={size} height={size} className="-rotate-90">
        <defs>
          <linearGradient id="ring-grad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#0071e3" />
            <stop offset="100%" stopColor="#42a5f5" />
          </linearGradient>
        </defs>
        {/* 轨道 */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="rgb(var(--wash) / 0.07)"
          strokeWidth={thickness}
        />
        {/* 进度 */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="url(#ring-grad)"
          strokeWidth={thickness}
          strokeLinecap="round"
          strokeDasharray={`${dash} ${circumference - dash}`}
          className="transition-all duration-700"
        />
      </svg>
      {/* 中心文字 */}
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-3xl font-bold text-grad">{clamped}</span>
        {label && <span className="mt-0.5 text-[11px] font-medium text-muted">{label}</span>}
        {caption && <span className="mt-0.5 text-[10px] text-muted-2">{caption}</span>}
      </div>
    </div>
  );
}
