import { useEffect, useState, type FormEvent } from 'react';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Input,
  Modal,
  Progress,
  Table,
  useToast,
  type Column,
} from '@/components/ui';
import { IconBuilding, IconPlus, IconRefresh, IconTrash } from '@/components/icons';
import { adminApi } from '@/lib/api';
import type { CreateTenantRequest, Tenant, TenantStatus } from '@/lib/types';
import { formatDateTime, formatNumber, formatSize } from '@/lib/utils/format';
import { IsolationDiagram } from './IsolationDiagram';

/** 租户状态 -> 标签样式 */
const STATUS_META: Record<TenantStatus, { label: string; tone: 'ok' | 'warn' | 'danger' }> = {
  ACTIVE: { label: '正常', tone: 'ok' },
  PENDING: { label: '待激活', tone: 'warn' },
  SUSPENDED: { label: '已停用', tone: 'danger' },
};

/** 新建租户表单初始值 */
const INITIAL_FORM: CreateTenantRequest = {
  code: '',
  name: '',
  quotaMb: 10240,
  ownerEmail: '',
};

/** 租户管理页：表格 + 创建弹窗 + 三级隔离架构图 */
export function TenantsPage() {
  const toast = useToast();

  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState<CreateTenantRequest>(INITIAL_FORM);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const load = () => {
    setLoading(true);
    adminApi
      .listTenants()
      .then(setTenants)
      .catch((e: Error) => toast.error(e.message || '加载租户失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    // 租户编码规范校验
    if (!/^[a-z][a-z0-9_-]{2,19}$/.test(form.code)) {
      setError('租户编码需为 3-20 位小写字母开头，可含数字、下划线或连字符');
      return;
    }

    setSubmitting(true);
    try {
      const created = await adminApi.createTenant(form);
      setTenants((prev) => [created, ...prev]);
      toast.success(`租户「${created.name}」创建成功`);
      setForm(INITIAL_FORM);
      setCreateOpen(false);
    } catch (err) {
      setError((err as Error).message || '创建失败');
    } finally {
      setSubmitting(false);
    }
  };

  /** 切换租户启用/停用状态 */
  const handleToggleStatus = async (t: Tenant) => {
    const next: TenantStatus = t.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE';
    try {
      const updated = await adminApi.updateTenantStatus(t.id, next);
      setTenants((prev) => prev.map((x) => (x.id === t.id ? updated : x)));
      toast.success(next === 'ACTIVE' ? '租户已启用' : '租户已停用');
    } catch (e) {
      toast.error((e as Error).message || '操作失败');
    }
  };

  const handleDelete = async (t: Tenant) => {
    if (!window.confirm(`确认删除租户「${t.name}」？其下全部用户、知识库与向量集合将被清除。`)) {
      return;
    }
    try {
      await adminApi.deleteTenant(t.id);
      setTenants((prev) => prev.filter((x) => x.id !== t.id));
      toast.success('租户已删除');
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  const columns: Column<Tenant>[] = [
    {
      key: 'name',
      title: '租户',
      render: (t) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-grad-soft text-accent">
            <IconBuilding className="h-4 w-4" />
          </span>
          <div className="min-w-0">
            <p className="truncate text-xs font-medium text-text">{t.name}</p>
            <p className="mt-0.5 font-mono text-[10px] text-muted-2">{t.code}</p>
          </div>
        </div>
      ),
    },
    {
      key: 'status',
      title: '状态',
      render: (t) => (
        <Badge tone={STATUS_META[t.status].tone} dot>
          {STATUS_META[t.status].label}
        </Badge>
      ),
    },
    {
      key: 'stats',
      title: '资源统计',
      render: (t) => (
        <div className="flex items-center gap-3 text-[11px] text-muted">
          <span>{t.userCount} 用户</span>
          <span className="text-muted-2">·</span>
          <span>{t.kbCount} 知识库</span>
          <span className="text-muted-2">·</span>
          <span>{formatNumber(t.docCount)} 文档</span>
        </div>
      ),
    },
    {
      key: 'quota',
      title: '存储配额',
      width: '170px',
      render: (t) => {
        const percent = (t.usedMb / t.quotaMb) * 100;
        return (
          <div>
            <div className="mb-1.5 flex items-center justify-between text-[10px]">
              <span className="text-muted">
                {formatSize(t.usedMb * 1024)} / {formatSize(t.quotaMb * 1024)}
              </span>
              <span
                className={
                  percent > 85 ? 'text-danger' : percent > 65 ? 'text-warn' : 'text-muted-2'
                }
              >
                {percent.toFixed(0)}%
              </span>
            </div>
            <Progress
              value={percent}
              tone={percent > 85 ? 'danger' : percent > 65 ? 'warn' : 'grad'}
            />
          </div>
        );
      },
    },
    {
      key: 'owner',
      title: '负责人',
      render: (t) => <span className="text-[11px] text-muted">{t.ownerEmail}</span>,
    },
    {
      key: 'created',
      title: '创建时间',
      render: (t) => <span className="text-[11px] text-muted">{formatDateTime(t.createdAt)}</span>,
    },
    {
      key: 'actions',
      title: '操作',
      align: 'right',
      render: (t) => (
        <div className="flex items-center justify-end gap-1.5">
          <Button variant="ghost" size="sm" onClick={() => void handleToggleStatus(t)}>
            {t.status === 'ACTIVE' ? '停用' : '启用'}
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => void handleDelete(t)}
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
      <div className="grid gap-5 xl:grid-cols-[1fr_400px]">
        {/* 租户表格 */}
        <Card padding="none">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
            <div>
              <h3 className="text-[15px] font-semibold text-text">租户列表</h3>
              <p className="mt-0.5 text-xs text-muted">共 {tenants.length} 个租户</p>
            </div>
            <div className="flex items-center gap-2.5">
              <Button variant="secondary" onClick={load} icon={<IconRefresh className="h-4 w-4" />}>
                刷新
              </Button>
              <Button onClick={() => setCreateOpen(true)} icon={<IconPlus className="h-4 w-4" />}>
                创建租户
              </Button>
            </div>
          </div>

          <Table
            columns={columns}
            data={tenants}
            rowKey={(t) => t.id}
            loading={loading}
            emptyText="暂无租户"
          />
        </Card>

        {/* 隔离架构图 */}
        <Card>
          <CardHeader title="三级数据隔离架构" subtitle="从租户到向量集合的逐层隔离设计" />
          <IsolationDiagram />
        </Card>
      </div>

      {/* 创建租户弹窗 */}
      <Modal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        title="创建租户"
        description="租户创建后处于待激活状态，需分配管理员账号后启用"
        footer={
          <>
            <Button variant="secondary" onClick={() => setCreateOpen(false)} disabled={submitting}>
              取消
            </Button>
            <Button onClick={handleCreate} loading={submitting}>
              创建租户
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

          <Input
            label="租户编码"
            placeholder="如 acme"
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value.toLowerCase() })}
            required
            hint="全局唯一，创建后不可修改，将作为向量集合名前缀"
          />
          <Input
            label="租户名称"
            placeholder="如 Acme 智能科技"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
          />
          <Input
            label="负责人邮箱"
            type="email"
            placeholder="admin@example.com"
            value={form.ownerEmail}
            onChange={(e) => setForm({ ...form, ownerEmail: e.target.value })}
            required
          />
          <Input
            label="存储配额（MB）"
            type="number"
            min={1024}
            step={1024}
            value={form.quotaMb}
            onChange={(e) => setForm({ ...form, quotaMb: Number(e.target.value) })}
            required
            hint="超出配额后将拒绝新文档上传"
          />
        </form>
      </Modal>
    </div>
  );
}
