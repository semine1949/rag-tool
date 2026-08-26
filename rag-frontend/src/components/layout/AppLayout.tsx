import { useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { Background } from './Background';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

/** 路由 -> 页面标题与副标题映射 */
const PAGE_META: Record<string, { title: string; subtitle: string }> = {
  '/dashboard': { title: '概览', subtitle: '平台运行状态与关键指标一览' },
  '/chat': { title: '智能问答', subtitle: '基于检索增强生成的知识库问答' },
  '/kbs': { title: '知识库管理', subtitle: '管理知识库配置、检索模式与向量集合' },
  '/docs': { title: '文档管理', subtitle: '上传文档并管理分块与索引状态' },
  '/tenants': { title: '租户管理', subtitle: '多租户配额、状态与三级数据隔离' },
  '/users': { title: '用户管理', subtitle: '租户内用户账号与角色分配' },
  '/roles': { title: '角色权限', subtitle: 'RBAC 四级角色权限矩阵与认证机制' },
};

/**
 * 主布局：左侧 Sidebar + 顶部 Topbar + 内容区
 * 内容通过 Outlet 渲染子路由
 */
export function AppLayout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const location = useLocation();
  const meta = PAGE_META[location.pathname] ?? { title: 'NebulaKB', subtitle: '' };

  return (
    <div className="min-h-screen">
      <Background />
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />

      {/* 内容区：桌面端为侧栏留出左边距 */}
      <div className="lg:pl-[248px]">
        <Topbar
          title={meta.title}
          subtitle={meta.subtitle}
          onMenuClick={() => setSidebarOpen(true)}
        />
        <main className="animate-fade p-5 lg:p-7">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
