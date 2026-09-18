import { useRef, useState, type DragEvent } from 'react';
import { Badge, Button, Progress, Select } from '@/components/ui';
import { IconUpload } from '@/components/icons';
import { CHUNK_STRATEGY_META } from '@/lib/rbac';
import {
  PARSER_MODEL_META,
  PARSER_MODEL_ORDER,
  resolveParserByFileName,
  type ParserModel,
} from '@/lib/parserRouting';
import type { ChunkStrategy, KnowledgeBase, UploadTask } from '@/lib/types';
import { formatSize } from '@/lib/utils/format';
import { cn } from '@/lib/utils/cn';

/** 支持的文件类型 */
const ACCEPT = '.pdf,.docx,.doc,.md,.txt,.xlsx,.csv,.pptx';
/** 单文件大小上限（MB） */
const MAX_SIZE_MB = 50;

export interface UploadZoneProps {
  kbs: KnowledgeBase[];
  /** 上传任务列表 */
  tasks: UploadTask[];
  onUpload: (files: File[], kbId: number, strategy: ChunkStrategy, parser: ParserModel) => void;
  /** 是否有上传权限 */
  disabled?: boolean;
}

/** 拖拽上传区：支持多文件、目标知识库、分块策略与解析模型选择 */
export function UploadZone({ kbs, tasks, onUpload, disabled }: UploadZoneProps) {
  const [dragging, setDragging] = useState(false);
  const [kbId, setKbId] = useState<string>(String(kbs[0]?.id ?? ''));
  const [strategy, setStrategy] = useState<ChunkStrategy>('text-model');
  /** 解析模型：auto 表示交由后端按扩展名自动识别 */
  const [parser, setParser] = useState<ParserModel>('auto');
  /** 最近一次选中/拖入的文件名，用于「自动识别结果」预览 */
  const [pending, setPending] = useState<string[]>([]);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  /** 解析模型提示文案：auto 展示路由总览，显式选择时展示该解析器说明 */
  const autoHint = PARSER_MODEL_META[parser].desc;

  /** 校验并提交文件 */
  const submit = (files: File[]) => {
    setError('');
    // 记录文件名供「自动识别结果」预览（解析模型为 auto 时展示）
    setPending(files.map((f) => f.name));
    if (!kbId) {
      setError('请先选择目标知识库');
      return;
    }
    // 过滤超限文件
    const oversize = files.filter((f) => f.size > MAX_SIZE_MB * 1024 * 1024);
    if (oversize.length > 0) {
      setError(`以下文件超过 ${MAX_SIZE_MB}MB 限制：${oversize.map((f) => f.name).join('、')}`);
    }
    const valid = files.filter((f) => f.size <= MAX_SIZE_MB * 1024 * 1024);
    if (valid.length > 0) onUpload(valid, Number(kbId), strategy, parser);
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragging(false);
    if (disabled) return;
    submit(Array.from(e.dataTransfer.files));
  };

  return (
    <div className="space-y-4">
      {/* 上传参数 */}
      <div className="grid gap-4 sm:grid-cols-2">
        <Select
          label="目标知识库"
          value={kbId}
          onChange={(e) => setKbId(e.target.value)}
          options={kbs.map((k) => ({ value: String(k.id), label: k.name }))}
          placeholder="请选择知识库"
          disabled={disabled}
        />
        <Select
          label="解析模型"
          value={parser}
          onChange={(e) => setParser(e.target.value as ParserModel)}
          options={PARSER_MODEL_ORDER.map((p) => ({
            value: p,
            label: PARSER_MODEL_META[p].label,
          }))}
          disabled={disabled}
          hint={autoHint}
        />
        <Select
          label="分块策略"
          value={strategy}
          onChange={(e) => setStrategy(e.target.value as ChunkStrategy)}
          options={(Object.keys(CHUNK_STRATEGY_META) as ChunkStrategy[]).map((s) => ({
            value: s,
            label: CHUNK_STRATEGY_META[s].label,
          }))}
          disabled={disabled}
          hint={CHUNK_STRATEGY_META[strategy].desc}
        />
      </div>

      {/* 自动识别预览：展示每个待上传文件将命中的解析器 */}
      {parser === 'auto' && pending.length > 0 && (
        <div className="rounded-xl border border-line bg-wash/[0.03] px-3.5 py-2.5">
          <p className="mb-1.5 text-[10px] font-medium tracking-wide text-muted-2">
            自动识别结果（按扩展名）
          </p>
          <ul className="space-y-1">
            {pending.map((f) => (
              <li key={f} className="flex items-center justify-between gap-3 text-[11px]">
                <span className="min-w-0 flex-1 truncate text-muted">{f}</span>
                <span className="shrink-0 text-accent">
                  {PARSER_MODEL_META[resolveParserByFileName(f)].label}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* 拖拽区 */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          if (!disabled) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        onClick={() => !disabled && inputRef.current?.click()}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed px-6 py-9 text-center transition-all',
          disabled
            ? 'cursor-not-allowed border-line opacity-50'
            : dragging
              ? 'border-accent/70 bg-accent/[0.07]'
              : 'border-line-2 bg-well/20 hover:border-accent/45 hover:bg-wash/[0.03]',
        )}
      >
        <span
          className={cn(
            'mb-3.5 flex h-12 w-12 items-center justify-center rounded-2xl transition-all',
            dragging ? 'scale-110 bg-grad text-onaccent' : 'bg-grad-soft text-accent',
          )}
        >
          <IconUpload className="h-5 w-5" />
        </span>
        <p className="text-sm font-medium text-text">
          {dragging ? '松开鼠标即可上传' : '拖拽文件到此处，或点击选择'}
        </p>
        <p className="mt-1.5 text-[11px] text-muted">
          支持 PDF / DOCX / MD / TXT / XLSX / PPTX，单文件最大 {MAX_SIZE_MB}MB
        </p>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPT}
          className="hidden"
          onChange={(e) => {
            submit(Array.from(e.target.files ?? []));
            e.target.value = '';
          }}
        />
      </div>

      {error && (
        <div className="rounded-xl border border-danger/30 bg-danger/10 px-3.5 py-2.5">
          <p className="text-xs leading-relaxed text-danger">{error}</p>
        </div>
      )}

      {/* 上传任务进度 */}
      {tasks.length > 0 && (
        <div className="space-y-2.5">
          {tasks.map((t) => (
            <div key={t.id} className="rounded-xl border border-line bg-wash/[0.03] px-3.5 py-3">
              <div className="mb-2 flex items-center justify-between gap-3">
                <p className="min-w-0 flex-1 truncate text-xs text-text">{t.fileName}</p>
                <div className="flex shrink-0 items-center gap-2">
                  <span className="text-[10px] text-muted-2">{formatSize(t.fileSizeKb)}</span>
                  {t.status === 'success' ? (
                    <Badge tone="ok">已完成</Badge>
                  ) : t.status === 'error' ? (
                    <Badge tone="danger">失败</Badge>
                  ) : (
                    <Badge tone="accent">{t.progress}%</Badge>
                  )}
                </div>
              </div>
              <Progress
                value={t.status === 'success' ? 100 : t.progress}
                tone={t.status === 'error' ? 'danger' : t.status === 'success' ? 'ok' : 'grad'}
              />
              {t.errorMsg && <p className="mt-1.5 text-[10px] text-danger">{t.errorMsg}</p>}
            </div>
          ))}
        </div>
      )}

      {!disabled && (
        <Button
          variant="secondary"
          size="sm"
          onClick={() => inputRef.current?.click()}
          icon={<IconUpload className="h-3.5 w-3.5" />}
        >
          选择文件
        </Button>
      )}
    </div>
  );
}
