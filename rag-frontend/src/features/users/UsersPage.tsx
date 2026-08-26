import { useEffect, useMemo, useState, type FormEvent } from 'react';
import {
  Badge,
  Button,
  Card,
  Checkbox,
  Input,
  Modal,
  Select,
  Table,
  Tabs,
  useToast,
  type Column,
} from '@/components/ui';
import { IconPlus, IconRefresh, IconSearch, IconTrash } from '@/components/icons';
import { adminApi } from '@/lib/api';
import { ALL_ROLES, ROLE_LABEL } from '@/lib/rbac';
import type {
  CreateUserRequest,
  RoleCode,
  Tenant,
  UserItem,
  UserStatus,
} from '@/lib/types';
import { formatDateTime, formatRelative, initials } from '@/lib/utils/format';

/** 用户状态 -> 标签样式 */
const STATUS_META: Record<UserStatus, { label: string; tone: 'ok' | 'warn' | 'danger' }> = {
  ACTIVE: { label: '正常', tone: 'ok' },
  LOCKED: { label: '已锁定', tone: 'warn' },
  DISABLED: { label: '已禁用', tone: 'danger' },
};

/** 角色 -> 标签配色 */
const ROLE_TONE: Record<RoleCode, 'accent' | 'accent2' | 'accent3' | 'neutral'> = {
  TENANT_ADMIN: 'accent2',
  KB_ADMIN: 'accent',
  CONTRIBUTOR: 'accent3',
  VIEWER: 'neutral',
};

/** 创建用户表单初始值 */
const INITIAL_FORM: CreateUserRequest = {
  username: '',
  email: '',
  password: '',
  tenantId: 0,
  roles: ['VIEWER'],
};

/** 用户管理页：表格 + 角色标签 + 创建弹窗 */
export function UsersPage() {
  const toast = useToast();

  const [users, setUsers] = useState<UserItem[]>([]);
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [roleFilter, setRoleFilter] = useState('all');
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState<CreateUserRequest>(INITIAL_FORM);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    Promise.all([adminApi.listUsers(), adminApi.listTenants()])
      .then(([userList, tenantList]) => {
        setUsers(userList);
        setTenants(tenantList);
        // 默认选中第一个租户
        setForm((f) => ({ ...f, tenantId: f.tenantId || tenantList[0]?.id || 0 }));
      })
      .catch((e: Error) => toast.error(e.message || '加载用户失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filtered = useMemo(() => {
    return users.filter((u) => {
      const matchKeyword =
        !keyword ||
        u.username.toLowerCase().includes(keyword.toLowerCase()) ||
        u.email.toLowerCase().includes(keyword.toLowerCase());
      const matchRole = roleFilter === 'all' || u.roles.includes(roleFilter as RoleCode);
      return matchKeyword && matchRole;
    });
  }, [users, keyword, roleFilter]);

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    if (form.roles.length === 0) {
      setError('请至少选择一个角色');
      return;
    }
    if (form.password.length < 6) {
      setError('初始密码长度至少 6 位');
      return;
    }

    setSubmitting(true);
    try {
      const created = await adminApi.createUser(form);
      setUsers((prev) => [created, ...prev]);
      toast.success(`用户「${created.username}」创建成功`);
      setForm({ ...INITIAL_FORM, tenantId: tenants[0]?.id ?? 0 });
      setCreateOpen(false);
    } catch (err) {
      setError((err as Error).message || '创建失败');
    } finally {
      setSubmitting(false);
    }
  };

  /** 锁定 / 解锁用户 */
  const handleToggleStatus = async (u: UserItem) => {
    const next: UserStatus = u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    try {
      const updated = await adminApi.updateUserStatus(u.id, next);
      setUsers((prev) => prev.map((x) => (x.id === u.id ? updated : x)));
      toast.success(next === 'ACTIVE' ? '用户已启用' : '用户已禁用');
    } catch (e) {
      toast.error((e as Error).message || '操作失败');
    }
  };

  const handleDelete = async (u: UserItem) => {
    if (!window.confirm(`确认删除用户「${u.username}」？`)) return;
    try {
      await adminApi.deleteUser(u.id);
      setUsers((prev) => prev.filter((x) => x.id !== u.id));
      toast.success('用户已删除');
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  /** 切换创建表单中的角色勾选 */
  const toggleRole = (role: RoleCode) => {
    setForm((f) => ({
      ...f,
      roles: f.roles.includes(role) ? f.roles.filter((r) => r !== role) : [...f.roles, role],
    }));
  };

  const columns: Column<UserItem>[] = [
    {
      key: 'user',
      title: '用户',
      render: (u) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-grad text-[10px] font-bold text-[#04121a]">
            {initials(u.username)}
          </span>
          <div className="min-w-0">
            <p className="truncate text-xs font-medium text-text">{u.username}</p>
            <p className="mt-0.5 truncate text-[10px] text-muted-2">{u.email}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'tenant',
      title: '所属租户',
      render: (u) => <span className="text-xs text-muted">{u.tenantName}</span>,
    },
    {
      key: 'roles',
      title: '角色',
      render: (u) => (
        <div className="flex flex-wrap gap-1.5">
          {u.roles.map((r) => (
            <Badge key={r} tone={ROLE_TONE[r]}>
              {ROLE_LABEL[r]}
            </Badge>
          ))}
        </div>
      ),
    },
    {
      key: 'status',
      title: '状态',
      render: (u) => (
        <Badge tone={STATUS_META[u.status].tone} dot>
          {STATUS_META[u.status].label}
        </Badge>
      ),
    },
    {
      key: 'lastLogin',
      title: '最后登录',
      render: (u) => (
        <span className="text-[11px] text-muted">
          {u.lastLoginAt ? formatRelative(u.lastLoginAt) : '从未登录'}
        </span>
      ),
    },
    {
      key: 'created',
      title: '创建时间',
      render: (u) => <span className="text-[11px] text-muted">{formatDateTime(u.createdAt)}</span>,
    },
    {
      key: 'actions',
      title: '操作',
      align: 'right',
      render: (u) => (
        <div className="flex items-center justify-end gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => void handleToggleStatus(u)}>
            {u.status === 'ACTIVE' ? '禁用' : '启用'}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => void handleDelete(u)}
            className="text-danger hover:bg-danger/10"
            aria-label="删除"
          >
            <IconTrash className="h-3.5 w-3.5" />
          </Button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-5">
      <Card padding="none">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
          <Tabs
            items={[
              { key: 'all', label: '全部', count: users.length },
              ...ALL_ROLES.map((r) => ({
                key: r,
                label: ROLE_LABEL[r],
                count: users.filter((u) => u.roles.includes(r)).length,
              })),
            ]}
            value={roleFilter}
            onChange={setRoleFilter}
          />

          <div className="flex flex-wrap items-center gap-2.5">
            <div className="w-[200px]">
              <Input
                placeholder="搜索用户名或邮箱"
                prefixIcon={<IconSearch className="h-4 w-4" />}
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </div>
            <Button variant="secondary" onClick={load} icon={<IconRefresh className="h-4 w-4" />}>
              刷新
            </Button>
            <Button onClick={() => setCreateOpen(true)} icon={<IconPlus className="h-4 w-4" />}>
              创建用户
            </Button>
          </div>
        </div>

        <Table
          columns={columns}
          data={filtered}
          rowKey={(u) => u.id}
          loading={loading}
          emptyText="暂无符合条件的用户"
        />
      </Card>

      {/* 创建用户弹窗 */}
      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="创建用户"
        description="用户首次登录后建议强制修改初始密码"
        footer={
          <>
            <Button variant="secondary" onClick={() => setCreateOpen(false)} disabled={submitting}>
              取消
            </Button>
            <Button onClick={handleCreate} loading={submitting}>
              创建用户
            </Button>
          </>
        }
      >
        <form onSubmit={handleCreate} className="space-y-4">
          {error && (
            <div className="rounded-xl border border-danger/30 bg-danger/10 px-3.5 py-2.5">
              <p className="text-xs text-danger">{error}</p>
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <Input
              label="用户名"
              placeholder="如 li.wei"
              value={form.username}
              onChange={(e) => setForm({ ...form, username: e.target.value })}
              required
            />
            <Input
              label="邮箱"
              type="email"
              placeholder="user@example.com"
              value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
              required
            />
          </div>

          <Input
            label="初始密码"
            type="password"
            placeholder="至少 6 位"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            required
          />

          <Select
            label="所属租户"
            value={String(form.tenantId)}
            onChange={(e) => setForm({ ...form, tenantId: Number(e.target.value) })}
            options={tenants.map((t) => ({ value: String(t.id), label: `${t.name}（${t.code}）` }))}
          />

          {/* 角色多选 */}
          <div>
            <label className="mb-2 block text-xs font-medium text-muted">分配角色</label>
            <div className="space-y-2">
              {ALL_ROLES.map((role) => (
                <label
                  key={role}
                  className="flex cursor-pointer items-center justify-between gap-3 rounded-xl border border-line bg-white/[0.03] px-3.5 py-2.5 transition-colors hover:bg-white/[0.06]"
                >
                  <Checkbox
                    checked={form.roles.includes(role)}
                    onChange={() => toggleRole(role)}
                    label={ROLE_LABEL[role]}
                  />
                  <Badge tone={ROLE_TONE[role]}>{role}</Badge>
                </label>
              ))}
            </div>
            <p className="mt-2 text-[10px] text-muted-2">
              可同时分配多个角色，最终权限为各角色权限的并集
            </p>
          </div>
        </form>
      </Modal>
    </div>
  );
}
