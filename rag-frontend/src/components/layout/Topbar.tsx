import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge } from '@/components/ui';
import {
  IconBell,
  IconLogout,
  IconMenu,
  IconSearch,
  IconUser,
} from '@/components/icons';
import { useAuth } from '@/features/auth/AuthContext';
import { ROLE_LABEL, highestRole } from '@/lib/rbac';
import { initials } from '@/lib/utils/format';

export interface TopbarProps {
  title: string;
  subtitle?: string;
  onMenuClick: () => void;
}

/** 顶部栏：面包屑标题 + 搜索 + 通知 + 用户菜单 */
export function Topbar({ title, subtitle, onMenuClick }: TopbarProps) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // 点击外部关闭用户菜单
  useEffect(() => {
    if (!menuOpen) return;
    const onClick = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', onClick);
    return () => document.removeEventListener('mousedown', onClick);
  }, [menuOpen]);

  const role = highestRole(user?.roles);

  const handleLogout = () => {
    logout();
    navigate('/login', { replace: true });
  };

  return (
    <header className="glass-bar sticky top-0 z-20 flex h-16 items-center gap-4 border-b border-line px-5 lg:px-7">
      {/* 移动端菜单按钮 */}
      <button
        onClick={onMenuClick}
        className="rounded-lg p-2 text-muted transition-colors hover:bg-white/[0.06] hover:text-text lg:hidden"
        aria-label="打开菜单"
      >
        <IconMenu className="h-5 w-5" />
      </button>

      <div className="min-w-0 flex-1">
        <h1 className="truncate text-[15px] font-semibold text-text">{title}</h1>
        {subtitle && <p className="truncate text-xs text-muted">{subtitle}</p>}
      </div>

      {/* 全局搜索（占位） */}
      <div className="relative hidden md:block">
        <IconSearch className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
        <input
          placeholder="搜索知识库、文档…"
          className="h-9 w-[220px] rounded-xl border border-line bg-black/25 pl-9 pr-3 text-xs text-text placeholder:text-muted-2 focus:border-accent/50 focus:outline-none focus:ring-2 focus:ring-accent/15"
        />
      </div>

      {/* 通知 */}
      <button
        className="relative rounded-lg p-2 text-muted transition-colors hover:bg-white/[0.06] hover:text-text"
        aria-label="通知"
      >
        <IconBell className="h-[18px] w-[18px]" />
        <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-danger" />
      </button>

      {/* 用户菜单 */}
      <div className="relative" ref={menuRef}>
        <button
          onClick={() => setMenuOpen((v) => !v)}
          className="flex items-center gap-2.5 rounded-xl border border-line bg-white/[0.04] py-1.5 pl-1.5 pr-3 transition-colors hover:border-line-2 hover:bg-white/[0.08]"
        >
          <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-grad text-[11px] font-bold text-[#04121a]">
            {initials(user?.username ?? '')}
          </span>
          <span className="hidden text-left sm:block">
            <span className="block text-xs font-medium leading-tight text-text">
              {user?.username}
            </span>
            <span className="block text-[10px] leading-tight text-muted">
              {user?.tenantName}
            </span>
          </span>
        </button>

        {menuOpen && (
          <div className="glass absolute right-0 top-[calc(100%+8px)] w-[240px] animate-pop overflow-hidden shadow-card">
            <div className="border-b border-line px-4 py-3.5">
              <p className="truncate text-sm font-semibold text-text">{user?.username}</p>
              <p className="mt-0.5 truncate text-xs text-muted">{user?.email}</p>
              <div className="mt-2.5 flex flex-wrap gap-1.5">
                {role && <Badge tone="accent">{ROLE_LABEL[role]}</Badge>}
                <Badge tone="neutral">{user?.tenantCode}</Badge>
              </div>
            </div>
            <div className="p-1.5">
              <button className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-xs text-muted transition-colors hover:bg-white/[0.06] hover:text-text">
                <IconUser className="h-4 w-4" />
                个人资料
              </button>
              <button
                onClick={handleLogout}
                className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-xs text-danger transition-colors hover:bg-danger/10"
              >
                <IconLogout className="h-4 w-4" />
                退出登录
              </button>
            </div>
          </div>
        )}
      </div>
    </header>
  );
}
