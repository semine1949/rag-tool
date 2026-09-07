import { IconBuilding, IconDatabase, IconLayers } from '@/components/icons';

/** 三级隔离层次定义 */
const LEVELS = [
  {
    icon: IconBuilding,
    level: 'L1',
    title: '租户隔离',
    subtitle: 'Tenant',
    desc: '所有数据表强制携带 tenantId，查询自动注入过滤条件，跨租户不可见',
    color: '#22d3ee',
    items: ['独立配额与计费', '用户体系互相隔离', '审计日志独立留存'],
  },
  {
    icon: IconDatabase,
    level: 'L2',
    title: '知识库隔离',
    subtitle: 'KnowledgeBase',
    desc: '租户内按业务域划分知识库，通过成员授权表控制访问范围',
    color: '#a855f7',
    items: ['独立检索模式配置', '成员级角色授权', '独立分块策略'],
  },
  {
    icon: IconLayers,
    level: 'L3',
    title: '向量集合隔离',
    subtitle: 'Collection',
    desc: '每个知识库对应独立命名的 Milvus 集合，物理层面隔离向量数据',
    color: '#3b82f6',
    items: ['独立索引与维度', '可单独重建/清空', '故障影响范围可控'],
  },
];

/** 三级数据隔离架构图 */
export function IsolationDiagram() {
  return (
    <div className="space-y-3">
      {LEVELS.map((lv, i) => {
        const Icon = lv.icon;
        return (
          <div key={lv.level} className="relative">
            {/* 层级之间的连接线 */}
            {i < LEVELS.length - 1 && (
              <span
                className="absolute left-[27px] top-[60px] h-[calc(100%-46px)] w-px"
                style={{ background: `linear-gradient(${lv.color}66, transparent)` }}
              />
            )}

            <div
              className="rounded-xl border bg-wash/[0.03] p-4 transition-all hover:bg-wash/[0.055]"
              style={{ borderColor: `${lv.color}33`, marginLeft: i * 14 }}
            >
              <div className="flex items-start gap-3.5">
                <span
                  className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border"
                  style={{
                    background: `${lv.color}1f`,
                    borderColor: `${lv.color}40`,
                    color: lv.color,
                  }}
                >
                  <Icon className="h-5 w-5" />
                </span>

                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span
                      className="rounded-md px-1.5 py-0.5 text-[10px] font-bold"
                      style={{ background: `${lv.color}1f`, color: lv.color }}
                    >
                      {lv.level}
                    </span>
                    <p className="text-sm font-semibold text-text">{lv.title}</p>
                    <span className="font-mono text-[10px] text-muted-2">{lv.subtitle}</span>
                  </div>

                  <p className="mt-1.5 text-[11px] leading-relaxed text-muted">{lv.desc}</p>

                  <div className="mt-2.5 flex flex-wrap gap-1.5">
                    {lv.items.map((it) => (
                      <span
                        key={it}
                        className="rounded-md border border-line bg-wash/[0.06] px-2 py-0.5 text-[10px] text-muted"
                      >
                        {it}
                      </span>
                    ))}
                  </div>
                </div>
              </div>
            </div>
          </div>
        );
      })}

      <div className="rounded-xl border border-line bg-well/20 px-3.5 py-3">
        <p className="text-[11px] leading-relaxed text-muted">
          请求进入后依次校验
          <span className="mx-1 text-accent">租户归属</span>→
          <span className="mx-1 text-accent-2">角色权限</span>→
          <span className="mx-1 text-accent-3">知识库授权</span>
          ，三层校验全部通过才允许访问对应向量集合。
        </p>
      </div>
    </div>
  );
}
