import { useState } from 'react';
import { Badge, useToast } from '@/components/ui';
import { IconCheck, IconCopy, IconSparkles, IconWarning } from '@/components/icons';
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

/** 将消息内容清洗为纯文本：剥离极简 markdown 标记（粗体 **、行内代码反引号），便于复制/粘贴 */
function stripMarkdown(text: string): string {
  return text
    .replace(/\*\*([^*]+)\*\*/g, '$1') // 去掉 **粗体**
    .replace(/`([^`]+)`/g, '$1') // 去掉行内代码反引号
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** 每条消息下方的复制按钮：点击把纯文本写入剪贴板，成功后短暂显示"已复制"反馈 */
function CopyButton({ text }: { text: string }) {
  const toast = useToast();
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    const plain = stripMarkdown(text);
    try {
      await navigator.clipboard.writeText(plain);
      setCopied(true);
      toast.success('已复制到剪贴板');
      setTimeout(() => setCopied(false), 2000);
    } catch {
      toast.error('复制失败，请手动选中复制');
    }
  }

  return (
    <button
      onClick={handleCopy}
      className="inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] text-muted-2 transition-colors hover:bg-wash/[0.06] hover:text-accent"
      aria-label="复制消息内容"
      title="复制消息内容"
    >
      {copied ? <IconCheck className="h-3.5 w-3.5" /> : <IconCopy className="h-3.5 w-3.5" />}
      {copied ? '已复制' : '复制'}
    </button>
  );
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
        <div className="flex max-w-[76%] flex-col items-end">
          <div className="rounded-2xl rounded-tr-md border border-accent/25 bg-grad-soft px-4 py-3">
            <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-text">
              {message.content}
            </p>
          </div>
          {/* 消息下方复制按钮 */}
          <div className="mt-0.5 flex items-center">
            <CopyButton text={message.content} />
          </div>
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

        {!message.error && (
          <div className="mt-1.5 flex items-center gap-1.5">
            {message.model && !message.streaming && <Badge tone="neutral">{message.model}</Badge>}
            {/* 消息下方复制按钮 */}
            {!message.streaming && <CopyButton text={message.content} />}
          </div>
        )}
      </div>
    </div>
  );
}
