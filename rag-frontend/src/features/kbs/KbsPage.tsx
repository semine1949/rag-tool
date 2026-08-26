import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Modal,
  SkeletonCard,
  Tabs,
  useToast,
} from '@/components/ui';
import {
  IconDatabase,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconSettings,
  IconTrash,
} from '@/components/icons';
import { adminApi } from '@/lib/api';
import { CHUNK_STRATEGY_META, RETRIEVAL_MODE_META } from '@/lib/rbac';
import type { CollectionStatus, CreateKbRequest, KnowledgeBase, ModelOption } from '@/lib/types';
import { formatDateTime, formatNumber } from '@/lib/utils/format';
import { useAuth } from '@/features/auth/AuthContext';
import { CreateKbModal } from './CreateKbModal';

/** 集合状态 -> 标签样式 */
const STATUS_META: Record<CollectionStatus, { label: string; tone: 'ok' | 'warn' | 'danger' | 'neutral' }> = {
  READY: { label: '已就绪', tone: 'ok' },
  PROCESSING: { label: '处理中', tone: 'warn' },
  UNINITIALIZED: { label: '未初始化', tone: 'neutral' },
  ERROR: { label: '异常', tone: 'danger' },
};

/** 集合运维操作定义 */
const COLLECTION_ACTIONS = [
  { key: 'init' as const, label: '初始化集合', desc: '创建向量集合与索引结构' },
  { key: 'reprocess' as const, label: '重建索引', desc: '按当前分块策略重跑全部文档' },
  { key: 'clear' as const, label: '清空数据', desc: '删除全部向量数据，保留集合' },
  { key: 'drop' as const, label: '删除集合', desc: '彻底移除向量集合与数据' },
];

/** 知识库管理页：卡片网格 + 新建弹窗 + 集合运维 */
export function KbsPage() {
  const toast = useToast();
  const { can } = useAuth();

  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [models, setModels] = useState<ModelOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [filter, setFilter] = useState('all');
  const [createOpen, setCreateOpen] = useState(false);
  /** 当前打开运维面板的知识库 */
  const [opsTarget, setOpsTarget] = useState<KnowledgeBase | null>(null);

  const load = () => {
    setLoading(true);
    Promise.all([adminApi.listKbs(), adminApi.models()])
      .then(([kbList, modelList]) => {
        setKbs(kbList);
        setModels(modelList);
      })
      .catch((e: Error) => toast.error(e.message || '加载知识库失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const filtered = useMemo(() => {
    return kbs.filter((kb) => {
      const matchKeyword =
        !keyword ||
        kb.name.toLowerCase().includes(keyword.toLowerCase()) ||
        kb.description.toLowerCase().includes(keyword.toLowerCase());
      const matchFilter = filter === 'all' || kb.retrievalMode === filter;
      return matchKeyword && matchFilter;
    });
  }, [kbs, keyword, filter]);

  const handleCreate = async (payload: CreateKbRequest) => {
    const created = await adminApi.createKb(payload);
    setKbs((prev) => [created, ...prev]);
    toast.success(`知识库「${created.name}」创建成功`);
  };

  const handleDelete = async (kb: KnowledgeBase) => {
    if (!window.confirm(`确认删除知识库「${kb.name}」？该操作将同时移除其向量集合与全部文档。`)) {
      return;
    }
    try {
      await adminApi.deleteKb(kb.id);
      setKbs((prev) => prev.filter((k) => k.id !== kb.id));
      toast.success('知识库已删除');
    } catch (e) {
      toast.error((e as Error).message || '删除失败');
    }
  };

  const handleCollectionAction = async (
    kb: KnowledgeBase,
    action: 'init' | 'clear' | 'drop' | 'reprocess',
  ) => {
    try {
      const updated = await adminApi.collectionAction(kb.id, action);
      setKbs((prev) => prev.map((k) => (k.id === kb.id ? updated : k)));
      setOpsTarget(null);
      toast.success('操作已提交');
    } catch (e) {
      toast.error((e as Error).message || '操作失败');
    }
  };

  return (
    <div className="space-y-5">
      {/* 工具栏 */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <div className="w-[240px]">
            <Input
              placeholder="搜索知识库名称或描述"
              prefixIcon={<IconSearch className="h-4 w-4" />}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <Tabs
            items={[
              { key: 'all', label: '全部', count: kbs.length },
              { key: 'HYBRID', label: '混合' },
              { key: 'VECTOR_ONLY', label: '向量' },
              { key: 'BM25_ONLY', label: '关键词' },
            ]}
            value={filter}
            onChange={setFilter}
          />
        </div>

        <div className="flex items-center gap-2.5">
          <Button variant="secondary" size="md" onClick={load} icon={<IconRefresh className="h-4 w-4" />}>
            刷新
          </Button>
          {can('kb:create') && (
            <Button onClick={() => setCreateOpen(true)} icon={<IconPlus className="h-4 w-4" />}>
              新建知识库
            </Button>
          )}
        </div>
      </div>

      {/* 卡片网格 */}
      {loading ? (
        <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }).map((_, i) => (
            <SkeletonCard key={i} lines={4} />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <Card>
          <EmptyState
            icon={<IconDatabase className="h-6 w-6" />}
            title="暂无知识库"
            description="创建第一个知识库，上传文档后即可开始智能问答。"
            action={
              can('kb:create') && (
                <Button onClick={() => setCreateOpen(true)} icon={<IconPlus className="h-4 w-4" />}>
                  新建知识库
                </Button>
              )
            }
          />
        </Card>
      ) : (
        <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((kb) => {
            const status = STATUS_META[kb.collectionStatus];
            const modeMeta = RETRIEVAL_MODE_META[kb.retrievalMode];

            return (
              <Card key={kb.id} hoverable className="flex flex-col">
                <div className="mb-3 flex items-start justify-between gap-3">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-grad-soft text-accent">
                    <IconDatabase className="h-5 w-5" />
                  </span>
                  <Badge tone={status.tone} dot>
                    {status.label}
                  </Badge>
                </div>

                <h3 className="truncate text-sm font-semibold text-text">{kb.name}</h3>
                <p className="clamp-2 mt-1.5 min-h-[32px] text-[11px] leading-relaxed text-muted">
                  {kb.description || '暂无描述'}
                </p>

                <div className="mt-3 flex flex-wrap gap-1.5">
                  <Badge
                    tone={
                      modeMeta.tone === 'accent'
                        ? 'accent'
                        : modeMeta.tone === 'accent2'
                          ? 'accent2'
                          : 'accent3'
                    }
                  >
                    {modeMeta.label}
                  </Badge>
                  <Badge tone="neutral">
                    {kb.chunkStrategy === 'text-model' ? '扁平分块' : '层级分块'}
                  </Badge>
                </div>

                {/* 统计信息 */}
                <div className="mt-4 grid grid-cols-3 gap-2 rounded-xl border border-line bg-black/20 px-3 py-2.5">
                  <div>
                    <p className="text-[10px] text-muted-2">文档</p>
                    <p className="text-xs font-semibold text-text">{formatNumber(kb.docCount)}</p>
                  </div>
                  <div>
                    <p className="text-[10px] text-muted-2">分块</p>
                    <p className="text-xs font-semibold text-text">{formatNumber(kb.chunkCount)}</p>
                  </div>
                  <div>
                    <p className="text-[10px] text-muted-2">Top-K</p>
                    <p className="text-xs font-semibold text-text">{kb.topK}</p>
                  </div>
                </div>

                <div className="mt-3 space-y-1 text-[10px] text-muted-2">
                  <p className="truncate">集合：{kb.collectionName}</p>
                  <p>模型：{kb.embeddingModel}</p>
                  <p>更新：{formatDateTime(kb.updatedAt)}</p>
                </div>

                {/* 操作区 */}
                <div className="mt-4 flex items-center gap-2 border-t border-line pt-3.5">
                  {can('kb:config') && (
                    <Button
                      variant="secondary"
                      size="sm"
                      className="flex-1"
                      onClick={() => setOpsTarget(kb)}
                      icon={<IconSettings className="h-3.5 w-3.5" />}
                    >
                      集合运维
                    </Button>
                  )}
                  {can('kb:delete') && (
                    <Button
                      variant="danger"
                      size="sm"
                      onClick={() => void handleDelete(kb)}
                      aria-label="删除"
                    >
                      <IconTrash className="h-3.5 w-3.5" />
                    </Button>
                  )}
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {/* 新建弹窗 */}
      <CreateKbModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSubmit={handleCreate}
        models={models}
      />

      {/* 集合运维弹窗 */}
      <Modal
        open={!!opsTarget}
        onClose={() => setOpsTarget(null)}
        title={`集合运维 · ${opsTarget?.name ?? ''}`}
        description={`集合名 ${opsTarget?.collectionName ?? ''}，请谨慎执行破坏性操作`}
      >
        <div className="space-y-3">
          {opsTarget && (
            <div className="mb-4 grid grid-cols-2 gap-3 rounded-xl border border-line bg-black/20 p-3.5">
              <div>
                <p className="text-[10px] text-muted-2">当前配置</p>
                <p className="mt-0.5 text-xs text-text">
                  {RETRIEVAL_MODE_META[opsTarget.retrievalMode].label}
                </p>
              </div>
              <div>
                <p className="text-[10px] text-muted-2">分块策略</p>
                <p className="mt-0.5 text-xs text-text">
                  {CHUNK_STRATEGY_META[opsTarget.chunkStrategy].label}
                </p>
              </div>
              <div>
                <p className="text-[10px] text-muted-2">分块长度 / 重叠</p>
                <p className="mt-0.5 text-xs text-text">
                  {opsTarget.chunkSize} / {opsTarget.chunkOverlap}
                </p>
              </div>
              <div>
                <p className="text-[10px] text-muted-2">向量权重</p>
                <p className="mt-0.5 text-xs text-text">{opsTarget.vectorWeight}</p>
              </div>
            </div>
          )}

          {COLLECTION_ACTIONS.map((action) => (
            <div
              key={action.key}
              className="flex items-center justify-between gap-4 rounded-xl border border-line bg-white/[0.03] px-3.5 py-3"
            >
              <div className="min-w-0">
                <p className="text-xs font-medium text-text">{action.label}</p>
                <p className="mt-0.5 text-[10px] text-muted">{action.desc}</p>
              </div>
              <Button
                variant={action.key === 'drop' || action.key === 'clear' ? 'danger' : 'secondary'}
                size="sm"
                onClick={() => opsTarget && void handleCollectionAction(opsTarget, action.key)}
              >
                执行
              </Button>
            </div>
          ))}
        </div>
      </Modal>
    </div>
  );
}
