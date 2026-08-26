import { Card } from '@/components/ui';
import type { DashboardMetric } from '@/lib/types';
import { cn } from '@/lib/utils/cn';

/** 指标卡配色，按顺序循环使用主色 */
const TONES = [
  { bg: 'from-accent/18', text: 'text-accent' },
  { bg: 'from-accent-2/18', text: 'text-accent-2' },
  { bg: 'from-accent-3/18', text: 'text-accent-3' },
  { bg: 'from-ok/18', text: 'text-ok' },
];

export interface MetricCardProps {
  metric: DashboardMetric;
  index: number;
  icon: React.ReactNode;
}

/** 概览指标卡：数值 + 环比变化 + 说明 */
export function MetricCard({ metric, index, icon }: MetricCardProps) {
  const tone = TONES[index % TONES.length];
  const positive = metric.delta >= 0;

  return (
    <Card hoverable className="relative overflow-hidden">
      {/* 右上角装饰渐变 */}
      <div
        className={cn(
          'pointer-events-none absolute -right-6 -top-6 h-24 w-24 rounded-full bg-gradient-to-br to-transparent',
          tone.bg,
        )}
      />
      <div className="relative">
        <div className="mb-3 flex items-center justify-between">
          <span
            className={cn(
              'flex h-9 w-9 items-center justify-center rounded-xl border border-line bg-white/[0.04]',
              tone.text,
            )}
          >
            {icon}
          </span>
          <span
            className={cn(
              'flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium',
              positive ? 'bg-ok/12 text-ok' : 'bg-danger/12 text-danger',
            )}
          >
            {positive ? '↑' : '↓'} {Math.abs(metric.delta)}%
          </span>
        </div>
        <p className="text-2xl font-bold tracking-tight text-text">{metric.value}</p>
        <p className="mt-1 text-xs font-medium text-muted">{metric.label}</p>
        <p className="mt-2.5 border-t border-line pt-2.5 text-[11px] text-muted-2">{metric.hint}</p>
      </div>
    </Card>
  );
}
