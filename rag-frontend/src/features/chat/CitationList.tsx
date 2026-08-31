import { useState } from 'react';
import { Badge } from '@/components/ui';
import { IconQuote } from '@/components/icons';
import type { Citation } from '@/lib/types';
import { cn } from '@/lib/utils/cn';

/** 相似度得分 -> 标签配色 */
function scoreTone(score: number) {
  if (score >= 0.85) return 'ok' as const;
  if (score >= 0.7) return 'warn' as const;
  return 'neutral' as const;
}

/** 引用溯源列表：可展开查看原文片段 */
export function CitationList({ citations }: { citations: Citation[] }) {
  const [expanded, setExpanded] = useState<number | null>(null);

  if (citations.length === 0) return null;

  return (
    <div className="mt-3.5 border-t border-line pt-3">
      <div className="mb-2 flex items-center gap-1.5">
        <IconQuote className="h-3.5 w-3.5 text-accent" />
        <span className="text-[11px] font-medium text-muted">
          引用溯源 · {citations.length} 处
        </span>
      </div>

      <div className="flex flex-wrap gap-2">
        {citations.map((c) => (
          <button
            key={c.index}
            onClick={() => setExpanded(expanded === c.index ? null : c.index)}
            className={cn(
              'group flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-[11px] transition-all',
              expanded === c.index
                ? 'border-accent/50 bg-accent/10 text-text'
                : 'border-line bg-white/[0.04] text-muted hover:border-accent/35 hover:text-text',
            )}
          >
            <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded bg-grad text-[9px] font-bold text-[#04121a]">
              {c.index}
            </span>
            <span className="max-w-[160px] truncate">{c.docName}</span>
            {c.chunkId && (
              <span className="max-w-[80px] truncate text-muted-2" title={c.chunkId}>
                #{c.chunkId.slice(-8)}
              </span>
            )}
            <Badge tone={scoreTone(c.score)} className="ml-0.5 !px-1.5 !py-0 !text-[10px]">
              {c.score.toFixed(2)}
            </Badge>
          </button>
        ))}
      </div>

      {/* 展开的引用详情 */}
      {expanded !== null && (
        <div className="mt-2.5 animate-fade rounded-xl border border-line bg-black/25 p-3.5">
          {(() => {
            const c = citations.find((x) => x.index === expanded);
            if (!c) return null;
            return (
              <>
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <Badge tone="accent">{c.kbName || c.docName}</Badge>
                  <span className="text-[11px] text-muted">
                    文档 {c.docName}
                    {c.chunkId ? ` · 分块 #${c.chunkId}` : ''}
                    {' · 相似度 '}
                    {(c.score * 100).toFixed(1)}%
                  </span>
                </div>
                <p className="text-[11px] leading-relaxed text-muted">{c.snippet}</p>
              </>
            );
          })()}
        </div>
      )}
    </div>
  );
}
