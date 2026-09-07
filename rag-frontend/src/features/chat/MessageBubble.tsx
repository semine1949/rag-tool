import { Badge } from '@/components/ui';
import { IconSparkles, IconWarning } from '@/components/icons';
import type { ChatMessage } from '@/lib/types';
import { initials } from '@/lib/utils/format';
import { CitationList } from './CitationList';

/**
 * 极简 Markdown 渲染
 * 仅支持 **粗体**、`行内代码` 与段落/列表换行，避免引入完整 markdown 依赖
 */
function renderContent(text: string) {
  return text.split('\n').map((line, i) => {
    if (!line.trim()) return <div key={i} className="h-2" />;

    // 拆分粗体与行内代码
    const parts = line.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).filter(Boolean);
    const isListItem = /^\s*(\d+\.|[-*])\s/.test(line);

    return (
      <p
        key={i}
        className={isListItem ? 'py-0.5 pl-3.5 text-[13px] leading-relaxed' : 'py-0.5 text-[13px] leading-relaxed'}
      >
        {parts.map((part, j) => {
          if (part.startsWith('**') && part.endsWith('**')) {
            return (
              <strong key={j} className="font-semibold text-text">
                {part.slice(2, -2)}
              </strong>
            );
          }
          if (part.startsWith('`') && part.endsWith('`')) {
            return (
              <code
                key={j}
                className="rounded bg-black/40 px-1.5 py-0.5 font-mono text-[11.5px] text-accent"
              >
                {part.slice(1, -1)}
              </code>
            );
          }
          return <span key={j}>{part}</span>;
        })}
      </p>
    );
  });
}

export interface MessageBubbleProps {
  message: ChatMessage;
  /** 当前用户名，用于头像 */
  username: string;
}

/** 单条对话气泡 */
export function MessageBubble({ message, username }: MessageBubbleProps) {
  const isUser = message.role === 'user';

  if (isUser) {
    return (
      <div className="flex justify-end gap-3">
        <div className="max-w-[76%] rounded-2xl rounded-tr-md border border-accent/25 bg-grad-soft px-4 py-3">
          <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-text">
            {message.content}
          </p>
        </div>
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border border-line bg-wash/[0.06] text-[10px] font-bold text-text">
          {initials(username)}
        </span>
      </div>
    );
  }

  return (
    <div className="flex gap-3">
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-grad text-onaccent shadow-glow">
        <IconSparkles className="h-4 w-4" />
      </span>

      <div className="min-w-0 max-w-[86%] flex-1">
        <div className="glass px-4 py-3">
          {message.error ? (
            <div className="flex items-start gap-2">
              <IconWarning className="mt-0.5 h-4 w-4 shrink-0 text-danger" />
              <p className="text-[13px] leading-relaxed text-danger">{message.error}</p>
            </div>
          ) : (
            <>
              <div className="text-muted">{renderContent(message.content)}</div>

              {/* 流式输出中的打字指示器 */}
              {message.streaming && (
                <div className="mt-1.5 flex items-center gap-1">
                  {[0, 1, 2].map((i) => (
                    <span
                      key={i}
                      className="h-1.5 w-1.5 animate-typing rounded-full bg-accent"
                      style={{ animationDelay: `${i * 0.18}s` }}
                    />
                  ))}
                </div>
              )}

              {message.citations && message.citations.length > 0 && (
                <CitationList citations={message.citations} />
              )}
            </>
          )}
        </div>

        {message.model && !message.streaming && (
          <div className="mt-1.5 flex items-center gap-2">
            <Badge tone="neutral">{message.model}</Badge>
          </div>
        )}
      </div>
    </div>
  );
}
