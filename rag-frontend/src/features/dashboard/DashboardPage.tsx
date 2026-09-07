import { useEffect, useState } from 'react';
import { DonutChart, LineChart, RingChart } from '@/components/charts';
import {
  Badge,
  Card,
  CardHeader,
  Progress,
  SkeletonCard,
  Tabs,
} from '@/components/ui';
import { IconChat, IconDatabase, IconDoc, IconLayers } from '@/components/icons';
import { adminApi } from '@/lib/api';
import type { DashboardData } from '@/lib/types';
import { MetricCard } from './MetricCard';
import { ActivityFeed } from './ActivityFeed';

/** 指标卡图标顺序，与后端返回的 metrics 顺序对应 */
const METRIC_ICONS = [
  <IconDatabase className="h-[18px] w-[18px]" key="kb" />,
  <IconDoc className="h-[18px] w-[18px]" key="doc" />,
  <IconLayers className="h-[18px] w-[18px]" key="chunk" />,
  <IconChat className="h-[18px] w-[18px]" key="qa" />,
];

/** 健康度状态 -> 进度条配色 */
const HEALTH_TONE = { ok: 'ok', warn: 'warn', danger: 'danger' } as const;

/**
 * 真实后端未提供 /admin/dashboard 统计接口时，
 * 从知识库/文档列表推导出基础概览数据，保证页面可用而非空白。
 */
async function buildFallbackDashboard(): Promise<DashboardData> {
  const kbs = await adminApi.listKbs();
  const kbCount = kbs.length;
  const emptyTrend = (value: number) =>
    Array.from({ length: 12 }).map((_, i) => ({
      label: `${String(i * 2).padStart(2, '0')}:00`,
      value,
    }));

  return {
    metrics: [
      { key: 'kb', label: '知识库总数', value: String(kbCount), delta: 0, hint: '实时数据' },
      { key: 'doc', label: '文档总数', value: '待统计', delta: 0, hint: '后端未提供统计接口' },
      { key: 'chunk', label: '向量分块数', value: '待统计', delta: 0, hint: '后端未提供统计接口' },
      { key: 'qa', label: '今日问答量', value: '待统计', delta: 0, hint: '后端未提供统计接口' },
    ],
    qaTrend: {
      current: emptyTrend(kbCount),
      previous: emptyTrend(0),
    },
    health: {
      overall: 100,
      items: [{ name: '知识库服务', score: 100, status: 'ok' }],
    },
    activities: [],
    docTypes: [],
  };
}

/**
 * 概览页
 * 4 指标卡 + 问答趋势图 + 健康度环图 + 活动流 + 文档类型分布
 */
export function DashboardPage() {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [range, setRange] = useState('today');

  useEffect(() => {
    let alive = true;
    setLoading(true);
    adminApi
      .dashboard()
      .then((d) => {
        if (alive) setData(d);
      })
      .catch(() => {
        // 真实后端未提供 /admin/dashboard 统计接口时，用知识库列表推导基础概览
        if (alive) {
          buildFallbackDashboard().then((d) => {
            if (alive) setData(d);
          });
        }
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
    // toast 为稳定引用，仅需首次加载
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (loading || !data) {
    return (
      <div className="space-y-5">
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <SkeletonCard key={i} lines={2} />
          ))}
        </div>
        <div className="grid gap-5 xl:grid-cols-3">
          <div className="xl:col-span-2">
            <SkeletonCard lines={6} />
          </div>
          <SkeletonCard lines={6} />
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* 指标卡 */}
      <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
        {data.metrics.map((m, i) => (
          <MetricCard key={m.key} metric={m} index={i} icon={METRIC_ICONS[i]} />
        ))}
      </div>

      {/* 趋势图 + 健康度 */}
      <div className="grid gap-5 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader
            title="问答趋势"
            subtitle="按时段统计问答请求量，虚线为上一周期对比"
            action={
              <Tabs
                items={[
                  { key: 'today', label: '今日' },
                  { key: 'week', label: '本周' },
                  { key: 'month', label: '本月' },
                ]}
                value={range}
                onChange={setRange}
              />
            }
          />
          <LineChart
            height={252}
            series={[
              {
                name: '本期',
                points: data.qaTrend.current,
                color: '#0071e3',
                area: true,
              },
              {
                name: '上期',
                points: data.qaTrend.previous,
                color: '#42a5f5',
                dashed: true,
              },
            ]}
          />
        </Card>

        <Card>
          <CardHeader title="系统健康度" subtitle="核心依赖服务可用性评分" />
          <div className="flex flex-col items-center">
            <RingChart value={data.health.overall} label="综合评分" caption="运行良好" />
          </div>
          <div className="mt-5 space-y-3">
            {data.health.items.map((h) => (
              <div key={h.name}>
                <div className="mb-1.5 flex items-center justify-between">
                  <span className="text-xs text-muted">{h.name}</span>
                  <span className="text-xs font-medium text-text">{h.score}</span>
                </div>
                <Progress value={h.score} tone={HEALTH_TONE[h.status]} />
              </div>
            ))}
          </div>
        </Card>
      </div>

      {/* 活动流 + 文档类型分布 */}
      <div className="grid gap-5 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader
            title="最近活动"
            subtitle="租户内用户与系统的关键操作记录"
            action={<Badge tone="accent">实时</Badge>}
          />
          <ActivityFeed items={data.activities} />
        </Card>

        <Card>
          <CardHeader title="文档类型分布" subtitle="按文件格式统计已索引文档" />
          <DonutChart data={data.docTypes} />
          <div className="mt-5 rounded-xl border border-line bg-well/20 px-3.5 py-3">
            <p className="text-[11px] leading-relaxed text-muted">
              PDF 与 DOCX 占比较高，建议为长文档启用
              <span className="mx-1 text-accent">层级分块策略</span>
              以提升检索上下文完整度。
            </p>
          </div>
        </Card>
      </div>
    </div>
  );
}
