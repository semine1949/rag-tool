import { NavLink } from 'react-router-dom';
import {
  IconBuilding,
  IconChat,
  IconDashboard,
  IconDatabase,
  IconDoc,
  IconShield,
  IconSparkles,
  IconUsers,
} from '@/components/icons';
import { useAuth } from '@/features/auth/AuthContext';
import type { PermissionKey } from '@/lib/types';
import { cn } from '@/lib/utils/cn';

/** 导航项定义 */
interface NavItem {
  to: string;
  label: string;
  icon: typeof IconDashboard;
  /** 所需权限，不填表示所有登录用户可见 */
  permission?: PermissionKey;
}

/** 侧栏分组：工作台 / 内容管理 / 租户与权限 */
const NAV_GROUPS: Array<{ title: string; items: NavItem[] }> = [
  {
    title: '工作台',
    items: [
      { to: '/dashboard', label: '概览', icon: IconDashboard },
      { to: '/chat', label: '智能问答', icon: IconChat, permission: 'chat:query' },
    ],
  },
  {
    title: '内容管理',
    items: [
      { to: '/kbs', label: '知识库管理', icon: IconDatabase, permission: 'doc:view' },
      { to: '/docs', label: '文档管理', icon: IconDoc, permission: 'doc:view' },
    ],
  },
  {
    title: '租户与权限',
    items: [
      { to: '/tenants', label: '租户管理', icon: IconBuilding, permission: 'tenant:manage' },
      { to: '/users', label: '用户管理', icon: IconUsers, permission: 'user:manage' },
      { to: '/roles', label: '角色权限', icon: IconShield, permission: 'role:assign' },
    ],
  },
];

export interface SidebarProps {
  /** 移动端抽屉是否打开 */
  open: boolean;
  onClose: () => void;
}

/** 左侧导航栏 */
export function Sidebar({ open, onClose }: SidebarProps) {
  const { can } = useAuth();

  return (
    <>
      {/* 移动端遮罩 */}
      {open && (
        <div className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm lg:hidden" onClick={onClose} />
      )}

      <aside
        className={cn(
          'glass-bar fixed inset-y-0 left-0 z-40 flex w-[248px] flex-col border-r border-line',
          'transition-transform duration-300 lg:translate-x-0',
          open ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        {/* 品牌区 */}
        <div className="flex h-16 shrink-0 items-center gap-2.5 border-b border-line px-5">
          <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-grad text-[#04121a] shadow-glow">
            <IconSparkles className="h-5 w-5" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-bold tracking-tight text-text">NebulaKB</p>
            <p className="truncate text-[10px] text-muted">企业级 RAG 平台</p>
          </div>
        </div>

        {/* 导航分组 */}
        <nav className="no-scrollbar flex-1 overflow-y-auto px-3 py-4">
          {NAV_GROUPS.map((group) => {
            // 过滤当前用户无权访问的菜单
            const visible = group.items.filter((it) => !it.permission || can(it.permission));
            if (visible.length === 0) return null;

            return (
              <div key={group.title} className="mb-5">
                <p className="mb-2 px-3 text-[10px] font-semibold uppercase tracking-wider text-muted-2">
                  {group.title}
                </p>
                <ul className="space-y-1">
                  {visible.map((item) => {
                    const Icon = item.icon;
                    return (
                      <li key={item.to}>
                        <NavLink
                          to={item.to}
                          onClick={onClose}
                          className={({ isActive }) =>
                            cn(
                              'group flex items-center gap-3 rounded-xl px-3 py-2.5 text-[13px] font-medium transition-all',
                              isActive
                                ? 'border border-accent/25 bg-grad-soft text-text'
                                : 'border border-transparent text-muted hover:bg-white/[0.05] hover:text-text',
                            )
                          }
                        >
                          {({ isActive }) => (
                            <>
                              <Icon
                                className={cn(
                                  'h-[18px] w-[18px] shrink-0 transition-colors',
                                  isActive ? 'text-accent' : 'text-muted group-hover:text-text',
                                )}
                              />
                              <span className="truncate">{item.label}</span>
                              {isActive && (
                                <span className="ml-auto h-1.5 w-1.5 rounded-full bg-accent shadow-glow" />
                              )}
                            </>
                          )}
                        </NavLink>
                      </li>
                    );
                  })}
                </ul>
              </div>
            );
          })}
        </nav>

        {/* 底部版本信息 */}
        <div className="shrink-0 border-t border-line px-5 py-3.5">
          <p className="text-[10px] text-muted-2">版本 v1.0.0</p>
        </div>
      </aside>
    </>
  );
}
