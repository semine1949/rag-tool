import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AppLayout } from '@/components/layout';
import { RequireAuth } from '@/features/auth/RequireAuth';
import { LoginPage } from '@/features/auth/LoginPage';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import { ChatPage } from '@/features/chat/ChatPage';
import { KbsPage } from '@/features/kbs/KbsPage';
import { DocsPage } from '@/features/docs/DocsPage';
import { TenantsPage } from '@/features/tenants/TenantsPage';
import { UsersPage } from '@/features/users/UsersPage';
import { RolesPage } from '@/features/roles/RolesPage';

/**
 * 路由表：8 个页面
 * /login 独立布局，其余页面共享 AppLayout 并受权限守卫保护
 */
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <DashboardPage /> },
      {
        path: 'chat',
        element: (
          <RequireAuth permission="chat:query">
            <ChatPage />
          </RequireAuth>
        ),
      },
      {
        path: 'kbs',
        element: (
          <RequireAuth permission="doc:view">
            <KbsPage />
          </RequireAuth>
        ),
      },
      {
        path: 'docs',
        element: (
          <RequireAuth permission="doc:view">
            <DocsPage />
          </RequireAuth>
        ),
      },
      {
        path: 'tenants',
        element: (
          <RequireAuth permission="tenant:manage">
            <TenantsPage />
          </RequireAuth>
        ),
      },
      {
        path: 'users',
        element: (
          <RequireAuth permission="user:manage">
            <UsersPage />
          </RequireAuth>
        ),
      },
      {
        path: 'roles',
        element: (
          <RequireAuth permission="role:assign">
            <RolesPage />
          </RequireAuth>
        ),
      },
    ],
  },
  // 兜底：未匹配路由回到概览
  { path: '*', element: <Navigate to="/dashboard" replace /> },
]);
