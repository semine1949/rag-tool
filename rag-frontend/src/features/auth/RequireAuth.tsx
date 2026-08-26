import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { EmptyState } from '@/components/ui';
import { IconLock } from '@/components/icons';
import { useAuth } from './AuthContext';
import type { PermissionKey } from '@/lib/types';

export interface RequireAuthProps {
  children: ReactNode;
  /** 访问该路由所需权限 */
  permission?: PermissionKey;
}

/**
 * 路由守卫
 * 未登录跳转登录页；已登录但无权限时展示 403 占位
 */
export function RequireAuth({ children, permission }: RequireAuthProps) {
  const { user, initializing, can } = useAuth();
  const location = useLocation();

  // 恢复登录态期间显示加载占位，避免闪烁跳转
  if (initializing) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <span className="h-8 w-8 animate-spin rounded-full border-2 border-accent border-t-transparent" />
      </div>
    );
  }

  if (!user) {
    // 记录来源路径，登录后可回跳
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (permission && !can(permission)) {
    return (
      <div className="glass p-6">
        <EmptyState
          icon={<IconLock className="h-6 w-6" />}
          title="无访问权限"
          description="当前角色不具备访问该模块的权限，如需开通请联系租户管理员。"
        />
      </div>
    );
  }

  return <>{children}</>;
}
