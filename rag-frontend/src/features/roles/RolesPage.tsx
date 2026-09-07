import { useEffect, useState } from 'react';
import {
  Badge,
  Card,
  CardHeader,
  SkeletonCard,
  useToast,
  type BadgeTone,
} from '@/components/ui';
import { IconLock, IconShield, IconUsers } from '@/components/icons';
import { adminApi } from '@/lib/api';
import { ROLE_PERMISSIONS } from '@/lib/rbac';
import type { Role, RoleCode } from '@/lib/types';
import { PermissionMatrix } from './PermissionMatrix';

/** 角色卡片配色 */
const ROLE_TONE: Record<RoleCode, { tone: BadgeTone; color: string }> = {
  SUPER_ADMIN: { tone: 'danger', color: '#ef4444' },
  TENANT_ADMIN: { tone: 'accent2', color: '#a855f7' },
  KB_ADMIN: { tone: 'accent', color: '#22d3ee' },
  CONTRIBUTOR: { tone: 'accent3', color: '#3b82f6' },
  VIEWER: { tone: 'neutral', color: '#8a98b5' },
};

/** 权限判定逻辑步骤 */
const DECISION_STEPS = [
  {
    step: 1,
    title: '解析 JWT 令牌',
    desc: '从 Authorization 头提取 accessToken，校验签名与有效期，解析出 userId、tenantId 与角色集合',
    fail: '令牌缺失或过期 → 401 触发自动刷新',
  },
  {
    step: 2,
    title: '校验租户归属',
    desc: '比对请求资源的 tenantId 与令牌中的 tenantId，所有数据查询强制注入租户过滤条件',
    fail: '跨租户访问 → 403 拒绝',
  },
  {
    step: 3,
    title: '校验角色权限',
    desc: '取用户在该租户绑定的角色权限（一用户一租户一角色），判断是否包含当前接口声明的权限点',
    fail: '权限点缺失 → 403 拒绝',
  },
  {
    step: 4,
    title: '校验知识库授权',
    desc: '针对知识库级接口，进一步查询成员授权表，确认用户在该知识库中的角色满足要求',
    fail: '未授权知识库 → 403 拒绝',
  },
];

/** 认证机制说明卡片 */
const AUTH_MECHANISMS = [
  {
    title: 'JWT 双令牌',
    items: [
      'accessToken 有效期 2 小时，随请求头携带',
      'refreshToken 有效期 7 天，仅用于换取新令牌',
      '过期前 60 秒自动静默刷新，并发请求共享同一刷新流程',
    ],
  },
  {
    title: '密码与账号安全',
    items: [
      '密码使用 BCrypt 加盐哈希存储，不可逆',
      '连续 5 次登录失败自动锁定账号 30 分钟',
      '禁用/锁定用户的令牌立即失效',
    ],
  },
  {
    title: '接口鉴权',
    items: [
      '后端以注解方式声明接口所需权限点',
      '前端按权限动态渲染菜单与操作按钮',
      '前端判定仅用于体验优化，最终以后端校验为准',
    ],
  },
];

/** 角色权限页：RBAC 矩阵 + 判定逻辑 + 认证机制 */
export function RolesPage() {
  const toast = useToast();
  const [roles, setRoles] = useState<Role[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    adminApi
      .listRoles()
      .then(setRoles)
      .catch((e: Error) => toast.error(e.message || '加载角色失败'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="space-y-5">
      {/* 角色概览卡片 */}
      {loading ? (
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <SkeletonCard key={i} lines={3} />
          ))}
        </div>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-4">
          {roles.map((role) => {
            const meta = ROLE_TONE[role.code];
            const permCount = ROLE_PERMISSIONS[role.code].length;

            return (
              <Card key={role.id} hoverable className="relative overflow-hidden">
                <div
                  className="pointer-events-none absolute -right-8 -top-8 h-24 w-24 rounded-full"
                  style={{ background: `radial-gradient(circle, ${meta.color}26, transparent 70%)` }}
                />
                <div className="relative">
                  <div className="mb-3 flex items-center justify-between">
                    <span
                      className="flex h-9 w-9 items-center justify-center rounded-xl border"
                      style={{
                        background: `${meta.color}1f`,
                        borderColor: `${meta.color}40`,
                        color: meta.color,
                      }}
                    >
                      <IconShield className="h-[18px] w-[18px]" />
                    </span>
                    <Badge tone={meta.tone}>L{role.level}</Badge>
                  </div>

                  <h3 className="text-sm font-semibold text-text">{role.name}</h3>
                  <code className="mt-0.5 block font-mono text-[10px] text-muted-2">
                    {role.code}
                  </code>
                  <p className="clamp-2 mt-2 min-h-[32px] text-[11px] leading-relaxed text-muted">
                    {role.description}
                  </p>

                  <div className="mt-3.5 flex items-center justify-between border-t border-line pt-3">
                    <span className="flex items-center gap-1.5 text-[11px] text-muted">
                      <IconUsers className="h-3.5 w-3.5" />
                      {role.userCount} 人
                    </span>
                    <span className="flex items-center gap-1.5 text-[11px] text-muted">
                      <IconLock className="h-3.5 w-3.5" />
                      {permCount} 项权限
                    </span>
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {/* 权限矩阵 */}
      <Card padding="none">
        <div className="border-b border-line px-5 py-4">
          <h3 className="text-[15px] font-semibold text-text">权限矩阵</h3>
          <p className="mt-0.5 text-xs text-muted">
            四级角色对 12 类权限点的授予情况，权限自上而下逐级收敛
          </p>
        </div>
        <PermissionMatrix />
      </Card>

      {/* 判定逻辑 + 认证机制 */}
      <div className="grid gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title="权限判定逻辑" subtitle="每个请求依次经过四层校验，任一层失败即拒绝" />
          <div className="space-y-3">
            {DECISION_STEPS.map((s, i) => (
              <div key={s.step} className="relative">
                {i < DECISION_STEPS.length - 1 && (
                  <span className="absolute left-[15px] top-[38px] h-[calc(100%-24px)] w-px bg-line" />
                )}
                <div className="flex gap-3.5">
                  <span className="relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-grad text-xs font-bold text-onaccent">
                    {s.step}
                  </span>
                  <div className="min-w-0 flex-1 rounded-xl border border-line bg-wash/[0.03] px-3.5 py-2.5">
                    <p className="text-xs font-semibold text-text">{s.title}</p>
                    <p className="mt-1 text-[11px] leading-relaxed text-muted">{s.desc}</p>
                    <p className="mt-1.5 flex items-center gap-1.5 text-[10px] text-danger">
                      <span className="h-1 w-1 rounded-full bg-danger" />
                      {s.fail}
                    </p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </Card>

        <Card>
          <CardHeader title="认证机制" subtitle="JWT 双令牌与账号安全策略" />
          <div className="space-y-3">
            {AUTH_MECHANISMS.map((m) => (
              <div key={m.title} className="rounded-xl border border-line bg-wash/[0.03] p-3.5">
                <p className="mb-2 flex items-center gap-2 text-xs font-semibold text-text">
                  <span className="h-1.5 w-1.5 rounded-full bg-accent" />
                  {m.title}
                </p>
                <ul className="space-y-1.5">
                  {m.items.map((it) => (
                    <li key={it} className="flex items-start gap-2 text-[11px] leading-relaxed text-muted">
                      <span className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-muted-2" />
                      {it}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
}
