import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  Checkbox,
  Select,
  Switch,
  useToast,
} from '@/components/ui';
import {
  IconDatabase,
  IconSend,
  IconSparkles,
  IconStop,
} from '@/components/icons';
import { adminApi, ragApi } from '@/lib/api';
import { RETRIEVAL_MODE_META } from '@/lib/rbac';
import type {
  ChatMessage,
  KnowledgeBase,
  ModelOption,
  RetrievalMode,
} from '@/lib/types';
import { useAuth } from '@/features/auth/AuthContext';
import { MessageBubble } from './MessageBubble';

/** 预设问题，便于快速体验 */
const SUGGESTIONS = [
  '混合检索的权重应该如何配置？',
  '两种分块策略有什么区别？',
  '平台的权限体系是怎样设计的？',
  '文档上传失败通常是什么原因？',
];

/**
 * 智能问答页
 * 左侧：知识库多选 + 模型与检索参数；右侧：流式对话 + 引用溯源
 */
export function ChatPage() {
  const { user } = useAuth();
  const toast = useToast();

  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [models, setModels] = useState<ModelOption[]>([]);
  const [selectedKbs, setSelectedKbs] = useState<number[]>([]);
  const [model, setModel] = useState('qwen-turbo');
  const [retrievalMode, setRetrievalMode] = useState<RetrievalMode>('HYBRID');
  const [topK, setTopK] = useState(5);
  const [temperature, setTemperature] = useState(0.3);
  const [withHistory, setWithHistory] = useState(true);
  const [streamMode, setStreamMode] = useState(true);

  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);

  /** 当前流式请求的取消函数 */
  const cancelRef = useRef<(() => void) | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  /** 多轮会话 ID（真实后端续接上下文用） */
  const sessionIdRef = useRef<string | null>(null);

  // 加载知识库与模型列表
  useEffect(() => {
    Promise.all([adminApi.listKbs(), adminApi.models()])
      .then(([kbList, modelList]) => {
        // 仅可用（已就绪）的知识库可参与问答
        const ready = kbList.filter((k) => k.collectionStatus === 'READY');
        setKbs(ready);
        setSelectedKbs(ready.slice(0, 2).map((k) => k.id));
        setModels(modelList);
      })
      .catch((e: Error) => toast.error(e.message || '加载配置失败'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 新消息时自动滚动到底部
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  // 组件卸载时中断进行中的流
  useEffect(() => () => cancelRef.current?.(), []);

  const chatModels = useMemo(() => models.filter((m) => m.type === 'chat'), [models]);

  const toggleKb = (id: number) => {
    setSelectedKbs((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  };

  /** 中断流式生成 */
  const handleStop = () => {
    cancelRef.current?.();
    cancelRef.current = null;
    setSending(false);
    setMessages((prev) =>
      prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)),
    );
  };

  const handleSend = async (question?: string) => {
    const q = (question ?? input).trim();
    if (!q || sending) return;

    if (selectedKbs.length === 0) {
      toast.warning('请至少选择一个知识库');
      return;
    }

    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      role: 'user',
      content: q,
      createdAt: new Date().toISOString(),
    };
    const assistantId = `a-${Date.now()}`;
    const assistantMsg: ChatMessage = {
      id: assistantId,
      role: 'assistant',
      content: '',
      streaming: true,
      createdAt: new Date().toISOString(),
      model,
    };

    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setInput('');
    setSending(true);

    const payload = {
      question: q,
      kbIds: selectedKbs,
      model,
      retrievalMode,
      topK,
      temperature,
      withHistory,
      // 多轮会话续接：携带上一轮的 sessionId（真实后端生效）
      sessionId: withHistory ? sessionIdRef.current ?? undefined : undefined,
    };

    // 同步模式：一次性返回完整答案
    if (!streamMode) {
      try {
        const resp = await ragApi.chat(payload);
        // 记录会话 ID 以便多轮续接
        if (resp.sessionId) sessionIdRef.current = resp.sessionId;
        setMessages((prev) =>
          prev.map((m) =>
            m.id === assistantId
              ? { ...m, content: resp.answer, citations: resp.citations, streaming: false }
              : m,
          ),
        );
      } catch (e) {
        const msg = (e as Error).message || '生成失败';
        setMessages((prev) =>
          prev.map((m) => (m.id === assistantId ? { ...m, streaming: false, error: msg } : m)),
        );
      } finally {
        setSending(false);
      }
      return;
    }

    // 流式模式：逐事件更新消息
    cancelRef.current = ragApi.chatStream(payload, (evt) => {
      switch (evt.type) {
        case 'citations':
          setMessages((prev) =>
            prev.map((m) => (m.id === assistantId ? { ...m, citations: evt.citations } : m)),
          );
          break;
        case 'content':
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId ? { ...m, content: m.content + evt.content } : m,
            ),
          );
          break;
        case 'done':
          setMessages((prev) =>
            prev.map((m) => (m.id === assistantId ? { ...m, streaming: false } : m)),
          );
          setSending(false);
          cancelRef.current = null;
          break;
        case 'error':
          setMessages((prev) =>
            prev.map((m) =>
              m.id === assistantId ? { ...m, streaming: false, error: evt.message } : m,
            ),
          );
          setSending(false);
          cancelRef.current = null;
          break;
      }
    });
  };

  const modeMeta = RETRIEVAL_MODE_META[retrievalMode];

  return (
    <div className="grid gap-5 xl:grid-cols-[300px_1fr]">
      {/* 左侧配置面板 */}
      <div className="space-y-5">
        <Card>
          <CardHeader
            title="知识库范围"
            subtitle={`已选 ${selectedKbs.length} / ${kbs.length} 个`}
            action={
              <button
                onClick={() =>
                  setSelectedKbs(
                    selectedKbs.length === kbs.length ? [] : kbs.map((k) => k.id),
                  )
                }
                className="text-[11px] text-accent hover:underline"
              >
                {selectedKbs.length === kbs.length ? '清空' : '全选'}
              </button>
            }
          />
          <div className="no-scrollbar max-h-[280px] space-y-2 overflow-y-auto">
            {kbs.length === 0 ? (
              <p className="py-6 text-center text-xs text-muted">暂无可用知识库</p>
            ) : (
              kbs.map((kb) => (
                <label
                  key={kb.id}
                  className="flex cursor-pointer items-start gap-2.5 rounded-xl border border-line bg-white/[0.03] px-3 py-2.5 transition-all hover:border-accent/30 hover:bg-white/[0.06]"
                >
                  <div className="pt-0.5">
                    <Checkbox
                      checked={selectedKbs.includes(kb.id)}
                      onChange={() => toggleKb(kb.id)}
                    />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-xs font-medium text-text">{kb.name}</p>
                    <div className="mt-1 flex flex-wrap items-center gap-1.5">
                      <Badge tone="neutral">{kb.docCount} 文档</Badge>
                      <Badge
                        tone={
                          RETRIEVAL_MODE_META[kb.retrievalMode].tone === 'accent'
                            ? 'accent'
                            : RETRIEVAL_MODE_META[kb.retrievalMode].tone === 'accent2'
                              ? 'accent2'
                              : 'accent3'
                        }
                      >
                        {RETRIEVAL_MODE_META[kb.retrievalMode].label}
                      </Badge>
                    </div>
                  </div>
                </label>
              ))
            )}
          </div>
        </Card>

        <Card>
          <CardHeader title="模型与检索参数" />
          <div className="space-y-4">
            <Select
              label="对话模型"
              value={model}
              onChange={(e) => setModel(e.target.value)}
              options={chatModels.map((m) => ({
                value: m.id,
                label: `${m.name}${m.available ? '' : '（不可用）'}`,
                disabled: !m.available,
              }))}
            />

            <Select
              label="检索模式"
              value={retrievalMode}
              onChange={(e) => setRetrievalMode(e.target.value as RetrievalMode)}
              options={(Object.keys(RETRIEVAL_MODE_META) as RetrievalMode[]).map((k) => ({
                value: k,
                label: RETRIEVAL_MODE_META[k].label,
              }))}
              hint={modeMeta.desc}
            />

            <div>
              <div className="mb-1.5 flex items-center justify-between">
                <label className="text-xs font-medium text-muted">Top-K 召回数</label>
                <span className="text-xs font-semibold text-accent">{topK}</span>
              </div>
              <input
                type="range"
                min={1}
                max={10}
                value={topK}
                onChange={(e) => setTopK(Number(e.target.value))}
                className="w-full accent-accent"
              />
            </div>

            <div>
              <div className="mb-1.5 flex items-center justify-between">
                <label className="text-xs font-medium text-muted">温度</label>
                <span className="text-xs font-semibold text-accent">
                  {temperature.toFixed(1)}
                </span>
              </div>
              <input
                type="range"
                min={0}
                max={1}
                step={0.1}
                value={temperature}
                onChange={(e) => setTemperature(Number(e.target.value))}
                className="w-full accent-accent"
              />
            </div>

            <div className="space-y-2.5 border-t border-line pt-3.5">
              <Switch checked={streamMode} onChange={setStreamMode} label="流式输出" />
              <Switch checked={withHistory} onChange={setWithHistory} label="携带历史上下文" />
            </div>
          </div>
        </Card>
      </div>

      {/* 右侧对话区 */}
      <Card padding="none" className="flex h-[calc(100vh-140px)] flex-col overflow-hidden">
        <div className="flex shrink-0 items-center justify-between border-b border-line px-5 py-3.5">
          <div className="flex items-center gap-2.5">
            <span className="flex h-8 w-8 items-center justify-center rounded-xl bg-grad text-[#04121a]">
              <IconSparkles className="h-4 w-4" />
            </span>
            <div>
              <p className="text-sm font-semibold text-text">知识库助手</p>
              <p className="text-[11px] text-muted">
                {selectedKbs.length} 个知识库 · {modeMeta.label} · Top-K {topK}
              </p>
            </div>
          </div>
          {messages.length > 0 && (
            <Button variant="ghost" size="sm" onClick={() => setMessages([])}>
              清空对话
            </Button>
          )}
        </div>

        {/* 消息列表 */}
        <div ref={scrollRef} className="flex-1 space-y-5 overflow-y-auto px-5 py-5">
          {messages.length === 0 ? (
            <div className="flex h-full flex-col items-center justify-center text-center">
              <span className="mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-grad-soft text-accent">
                <IconDatabase className="h-6 w-6" />
              </span>
              <h3 className="text-sm font-semibold text-text">开始知识库问答</h3>
              <p className="mt-1.5 max-w-sm text-xs leading-relaxed text-muted">
                系统将在选中的知识库中检索相关分块，并基于检索结果生成带引用溯源的回答。
              </p>
              <div className="mt-6 flex max-w-md flex-wrap justify-center gap-2">
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s}
                    onClick={() => handleSend(s)}
                    className="rounded-xl border border-line bg-white/[0.04] px-3 py-2 text-[11px] text-muted transition-all hover:-translate-y-0.5 hover:border-accent/40 hover:text-text"
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
          ) : (
            messages.map((m) => (
              <MessageBubble key={m.id} message={m} username={user?.username ?? ''} />
            ))
          )}
        </div>

        {/* 输入区 */}
        <div className="shrink-0 border-t border-line px-5 py-4">
          <div className="flex items-end gap-3">
            <textarea
              rows={1}
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                // Enter 发送，Shift+Enter 换行
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void handleSend();
                }
              }}
              placeholder="输入你的问题，Enter 发送，Shift + Enter 换行…"
              className="max-h-32 min-h-[42px] flex-1 resize-none rounded-xl border border-line bg-black/25 px-3.5 py-2.5 text-[13px] leading-relaxed text-text placeholder:text-muted-2 focus:border-accent/60 focus:outline-none focus:ring-2 focus:ring-accent/15"
            />
            {sending ? (
              <Button variant="danger" onClick={handleStop} icon={<IconStop className="h-4 w-4" />}>
                停止
              </Button>
            ) : (
              <Button
                onClick={() => void handleSend()}
                disabled={!input.trim()}
                icon={<IconSend className="h-4 w-4" />}
              >
                发送
              </Button>
            )}
          </div>
          <p className="mt-2 text-[10px] text-muted-2">
            回答由大模型基于检索内容生成，请结合引用原文核对关键信息。
          </p>
        </div>
      </Card>
    </div>
  );
}
