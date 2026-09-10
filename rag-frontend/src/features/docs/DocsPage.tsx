import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Input,
  Select,
  Table,
  Tabs,
  useToast,
  type Column,
} from '@/components/ui';
import { IconDoc, IconRefresh, IconSearch, IconTrash } from '@/components/icons';
import { adminApi, ragApi } from '@/lib/api';
import type {
  ChunkStrategy,
  DocStatus,
  DocumentItem,
  KnowledgeBase,
  UploadTask,
} from '@/lib/types';
import { formatDateTime, formatNumber, formatSize } from '@/lib/utils/format';
import { useAuth } from '@/features/auth/AuthContext';
import { UploadZone } from './UploadZone';

/** 文档状态 -> 标签样式 */
const STATUS_META: Record<DocStatus, { label: string; tone: 'ok' | 'warn' | 'danger' | 'neutral' }> = {
  INDEXED: { label: '已索引', tone: 'ok' },
  PROCESSING: { label: '处理中', tone: 'warn' },
  PENDING: { label: '排队中', tone: 'neutral' },
  FAILED: { label: '失败', tone: 'danger' },
};

/** 文件类型 -> 标签配色 */
const TYPE_TONE: Record<string, 'accent' | 'accent2' | 'accent3' | 'ok' | 'warn' | 'neutral'> = {
  pdf: 'accent',
  docx: 'accent2',
  doc: 'accent2',
  md: 'accent3',
  txt: 'warn',
  xlsx: 'ok',
  csv: 'ok',
};

/** 文档管理页：拖拽上传 + 文档表格 */
export function DocsPage() {
  const toast = useToast();
  const { can } = useAuth();

  const [docs, setDocs] = useState<DocumentItem[]>([]);
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [tasks, setTasks] = useState<UploadTask[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [kbFilter, setKbFilter] = useState('all');

  /**
   * 加载知识库与文档。
   * 真实后端：/rag/documents 必须传 kbId，因此对每个知识库分别拉取后合并。
   */
  const load = async () => {
    setLoading(true);
    try {
      const kbList = await adminApi.listKbs();
      setKbs(kbList);

      // 真实后端：逐知识库拉取文档并合并，忽略单个库的异常
      // 因文档接口只按 kbId 返回、不含知识库名，这里用已获取的 kb.name 填充每行 kbName
      const grouped = await Promise.all(
        kbList.map(async (kb) => {
          try {
            return await ragApi.documents(kb.id, kb.name);
          } catch {
            return [];
          }
        }),
      );
      setDocs(grouped.flat());
    } catch (e) {
      toast.error((e as Error).message || '加载文档失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filtered = useMemo(() => {
    return docs.filter((d) => {
      const matchKeyword = !keyword || d.fileName.toLowerCase().includes(keyword.toLowerCase());
      const matchStatus = statusFilter === 'all' || d.status === statusFilter;
      const matchKb = kbFilter === 'all' || String(d.kbId) === kbFilter;
      return matchKeyword && matchStatus && matchKb;
    });
  }, [docs, keyword, statusFilter, kbFilter]);

  /** 批量上传：逐个文件跟踪进度 */
  const handleUpload = (files: File[], kbId: number, strategy: ChunkStrategy) => {
    const kb = kbs.find((k) => k.id === kbId);
    files.forEach((file) => {
      const taskId = `${file.name}-${Date.now()}-${Math.random()}`;
      setTasks((prev) => [
        ...prev,
        {
          id: taskId,
          fileName: file.name,
          fileSizeKb: Math.round(file.size / 1024),
          progress: 0,
          status: 'uploading',
        },
      ]);

      ragApi
        .upload(
          file,
          {
            kbId,
            chunkStrategy: strategy,
            chunkSize: kb?.chunkSize ?? 800,
            chunkOverlap: kb?.chunkOverlap ?? 120,
          },
          (percent) => {
            setTasks((prev) =>
              prev.map((t) => (t.id === taskId ? { ...t, progress: percent } : t)),
            );
          },
        )
        .then((doc) => {
          setTasks((prev) =>
            prev.map((t) => (t.id === taskId ? { ...t, progress: 100, status: 'success' } : t)),
          );
          // 上传乐观插入：文档接口不含 kbName，用当前知识库名补齐该行所属知识库
          setDocs((prev) => [{ ...doc, kbName: kb?.name ?? '' }, ...prev]);
          toast.success(`「${file.name}」上传成功，正在后台索引`);
          // 3 秒后移除已完成任务，保持界面整洁
          window.setTimeout(() => {
            setTasks((prev) => prev.filter((t) => t.id !== taskId));
          }, 3000);
        })
        .catch((e: Error) => {
          setTasks((prev) =>
            prev.map((t) =>
              t.id === taskId ? { ...t, status: 'error', errorMsg: e.message } : t,
            ),
          );
          toast.error(`「${file.name}」上传失败：${e.message}`);
        });
    });
  };

  const handleDelete = async (doc: DocumentItem) => {
    if (!window.confirm(`确认删除文档「${doc.fileName}」及其全部向量分块？删除后不可恢复。`)) return;
    try {
      await ragApi.deleteDocument(doc.id);
      setDocs((prev) => prev.filter((d) => d.id !== doc.id));
      toast.success('文档已删除');
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  const handleReprocess = async (doc: DocumentItem) => {
    try {
      await adminApi.reprocessDoc(doc.id);
      // 置为处理中，等待后台完成
      setDocs((prev) =>
        prev.map((d) => (d.id === doc.id ? { ...d, status: 'PROCESSING' as const } : d)),
      );
      toast.success('已提交重建索引任务');
    } catch (e) {
      toast.error((e as Error).message || '操作失败');
    }
  };

  const columns: Column<DocumentItem>[] = [
    {
      key: 'name',
      title: '文档名称',
      render: (d) => (
        <div className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-line bg-wash/[0.04] text-muted">
            <IconDoc className="h-4 w-4" />
          </span>
          <div className="min-w-0">
            <p className="max-w-[260px] truncate text-xs font-medium text-text">{d.fileName}</p>
            <p className="mt-0.5 text-[10px] text-muted-2">
              {formatSize(d.fileSizeKb)} · {d.uploadedBy}
            </p>
          </div>
        </div>
      ),
    },
    {
      key: 'type',
      title: '类型',
      render: (d) => <Badge tone={TYPE_TONE[d.fileType] ?? 'neutral'}>{d.fileType.toUpperCase()}</Badge>,
    },
    {
      key: 'kb',
      title: '所属知识库',
      render: (d) => <span className="text-xs text-muted">{d.kbName}</span>,
    },
    {
      key: 'chunks',
      title: '分块数',
      align: 'right',
      render: (d) => (
        <span className="text-xs font-medium text-text">
          {d.chunkCount > 0 ? formatNumber(d.chunkCount) : '—'}
        </span>
      ),
    },
    {
      key: 'version',
      title: '版本',
      align: 'center',
      render: (d) => <Badge tone="neutral">v{d.version}</Badge>,
    },
    {
      key: 'status',
      title: '状态',
      render: (d) => (
        <div>
          <Badge tone={STATUS_META[d.status].tone} dot>
            {STATUS_META[d.status].label}
          </Badge>
          {d.errorMsg && (
            <p className="mt-1 max-w-[180px] truncate text-[10px] text-danger" title={d.errorMsg}>
              {d.errorMsg}
            </p>
          )}
        </div>
      ),
    },
    {
      key: 'time',
      title: '上传时间',
      render: (d) => <span className="text-[11px] text-muted">{formatDateTime(d.uploadedAt)}</span>,
    },
    {
      key: 'actions',
      title: '操作',
      align: 'right',
      render: (d) => (
        <div className="flex items-center justify-end gap-1.5">
          {can('doc:reprocess') && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => void handleReprocess(d)}
              disabled={d.status === 'PROCESSING'}
              aria-label="重建索引"
            >
              <IconRefresh className="h-3.5 w-3.5" />
            </Button>
          )}
          {can('doc:delete') && (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => void handleDelete(d)}
              className="text-danger hover:bg-danger/10"
              aria-label="删除"
            >
              <IconTrash className="h-3.5 w-3.5" />
            </Button>
          )}
        </div>
      ),
    },
  ];

  // 各状态数量统计
  const counts = useMemo(
    () => ({
      all: docs.length,
      INDEXED: docs.filter((d) => d.status === 'INDEXED').length,
      PROCESSING: docs.filter((d) => d.status === 'PROCESSING').length,
      FAILED: docs.filter((d) => d.status === 'FAILED').length,
    }),
    [docs],
  );

  return (
    <div className="space-y-5">
      {/* 上传区 */}
      <Card>
        <CardHeader
          title="上传文档"
          subtitle="文件将按所选分块策略解析、切分并写入向量集合"
          action={<Badge tone="accent">{kbs.length} 个知识库可选</Badge>}
        />
        <UploadZone
          kbs={kbs}
          tasks={tasks}
          onUpload={handleUpload}
          disabled={!can('doc:upload')}
        />
        {!can('doc:upload') && (
          <p className="mt-3 text-[11px] text-warn">当前角色无上传权限，请联系知识库管理员。</p>
        )}
      </Card>

      {/* 文档列表 */}
      <Card padding="none">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
          <Tabs
            items={[
              { key: 'all', label: '全部', count: counts.all },
              { key: 'INDEXED', label: '已索引', count: counts.INDEXED },
              { key: 'PROCESSING', label: '处理中', count: counts.PROCESSING },
              { key: 'FAILED', label: '失败', count: counts.FAILED },
            ]}
            value={statusFilter}
            onChange={setStatusFilter}
          />
          <div className="flex flex-wrap items-center gap-2.5">
            <div className="w-[150px]">
              <Select
                value={kbFilter}
                onChange={(e) => setKbFilter(e.target.value)}
                options={[
                  { value: 'all', label: '全部知识库' },
                  ...kbs.map((k) => ({ value: String(k.id), label: k.name })),
                ]}
              />
            </div>
            <div className="w-[200px]">
              <Input
                placeholder="搜索文档名称"
                prefixIcon={<IconSearch className="h-4 w-4" />}
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </div>
            <Button variant="secondary" onClick={load} icon={<IconRefresh className="h-4 w-4" />}>
              刷新
            </Button>
          </div>
        </div>

        <Table
          columns={columns}
          data={filtered}
          rowKey={(d) => d.id}
          loading={loading}
          emptyText="暂无符合条件的文档"
        />
      </Card>
    </div>
  );
}
