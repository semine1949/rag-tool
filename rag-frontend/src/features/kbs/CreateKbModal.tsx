import { useState, type FormEvent } from 'react';
import { Button, Input, Modal, Select, Textarea } from '@/components/ui';
import { CHUNK_STRATEGY_META, RETRIEVAL_MODE_META } from '@/lib/rbac';
import type {
  ChunkStrategy,
  CreateKbRequest,
  ModelOption,
  RetrievalMode,
} from '@/lib/types';
import { cn } from '@/lib/utils/cn';

export interface CreateKbModalProps {
  open: boolean;
  onClose: () => void;
  onSubmit: (payload: CreateKbRequest) => Promise<void>;
  /** 可选的 Embedding 模型 */
  models: ModelOption[];
}

/** 表单初始值 */
const INITIAL: CreateKbRequest = {
  name: '',
  description: '',
  embeddingModel: 'text-embedding-v3',
  retrievalMode: 'HYBRID',
  chunkStrategy: 'text-model',
  chunkSize: 800,
  chunkOverlap: 120,
};

/** 新建知识库弹窗：名称 / Embedding 模型 / 检索模式 / 分块策略 */
export function CreateKbModal({ open, onClose, onSubmit, models }: CreateKbModalProps) {
  const [form, setForm] = useState<CreateKbRequest>(INITIAL);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const embeddingModels = models.filter((m) => m.type === 'embedding');

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    if (form.chunkOverlap >= form.chunkSize) {
      setError('重叠长度必须小于分块长度');
      return;
    }

    setSubmitting(true);
    try {
      await onSubmit(form);
      setForm(INITIAL);
      onClose();
    } catch (err) {
      setError((err as Error).message || '创建失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="新建知识库"
      description="创建后系统将初始化独立的向量集合，可随时调整检索配置"
      size="lg"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            取消
          </Button>
          <Button onClick={handleSubmit} loading={submitting}>
            创建知识库
          </Button>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-5">
        {error && (
          <div className="rounded-xl border border-danger/30 bg-danger/10 px-3.5 py-2.5">
            <p className="text-xs text-danger">{error}</p>
          </div>
        )}

        <Input
          label="知识库名称"
          placeholder="如：产品技术文档库"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          required
        />

        <Textarea
          label="描述"
          rows={2}
          placeholder="简要说明该知识库的用途与内容范围"
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
        />

        <Select
          label="Embedding 模型"
          value={form.embeddingModel}
          onChange={(e) => setForm({ ...form, embeddingModel: e.target.value })}
          options={embeddingModels.map((m) => ({
            value: m.id,
            label: `${m.name}${m.dimension ? ` · ${m.dimension} 维` : ''}`,
            disabled: !m.available,
          }))}
          hint="创建后不可更改，切换模型需重建向量集合"
        />

        {/* 检索模式：卡片式单选 */}
        <div>
          <label className="mb-2 block text-xs font-medium text-muted">检索模式</label>
          <div className="grid gap-2.5 sm:grid-cols-3">
            {(Object.keys(RETRIEVAL_MODE_META) as RetrievalMode[]).map((mode) => {
              const meta = RETRIEVAL_MODE_META[mode];
              const active = form.retrievalMode === mode;
              return (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setForm({ ...form, retrievalMode: mode })}
                  className={cn(
                    'rounded-xl border px-3 py-2.5 text-left transition-all',
                    active
                      ? 'border-accent/50 bg-grad-soft'
                      : 'border-line bg-white/[0.03] hover:border-line-2 hover:bg-white/[0.06]',
                  )}
                >
                  <p className={cn('text-xs font-semibold', active ? 'text-text' : 'text-muted')}>
                    {meta.label}
                  </p>
                  <p className="mt-1 text-[10px] leading-relaxed text-muted-2">{meta.desc}</p>
                </button>
              );
            })}
          </div>
        </div>

        {/* 分块策略：卡片式单选 */}
        <div>
          <label className="mb-2 block text-xs font-medium text-muted">分块策略</label>
          <div className="grid gap-2.5 sm:grid-cols-2">
            {(Object.keys(CHUNK_STRATEGY_META) as ChunkStrategy[]).map((s) => {
              const meta = CHUNK_STRATEGY_META[s];
              const active = form.chunkStrategy === s;
              return (
                <button
                  key={s}
                  type="button"
                  onClick={() => setForm({ ...form, chunkStrategy: s })}
                  className={cn(
                    'rounded-xl border px-3 py-2.5 text-left transition-all',
                    active
                      ? 'border-accent-2/50 bg-grad-soft'
                      : 'border-line bg-white/[0.03] hover:border-line-2 hover:bg-white/[0.06]',
                  )}
                >
                  <p className={cn('text-xs font-semibold', active ? 'text-text' : 'text-muted')}>
                    {meta.label}
                  </p>
                  <p className="mt-1 text-[10px] leading-relaxed text-muted-2">{meta.desc}</p>
                </button>
              );
            })}
          </div>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Input
            label="分块长度（字符）"
            type="number"
            min={100}
            max={4000}
            value={form.chunkSize}
            onChange={(e) => setForm({ ...form, chunkSize: Number(e.target.value) })}
            hint="建议 500 ~ 1000"
          />
          <Input
            label="重叠长度（字符）"
            type="number"
            min={0}
            max={1000}
            value={form.chunkOverlap}
            onChange={(e) => setForm({ ...form, chunkOverlap: Number(e.target.value) })}
            hint="建议为分块长度的 10% ~ 20%"
          />
        </div>
      </form>
    </Modal>
  );
}
