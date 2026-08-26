import type {
  ChatRequest,
  ChatResponse,
  Citation,
  DocumentItem,
  MultiSearchRequest,
  SearchHit,
  SearchRequest,
  StreamEvent,
  UploadOptions,
} from '@/lib/types';
import { API_BASE_URL, authHeaders, request, USE_MOCK } from './client';
import { mockServer } from './mock/server';

/**
 * RAG 问答与检索接口
 * 后端端点：/api/rag/chat、/api/rag/chat/stream、/api/rag/upload、/search、/multi-search、/documents
 */
export const ragApi = {
  /** 同步问答 */
  chat(payload: ChatRequest): Promise<ChatResponse> {
    if (USE_MOCK) return mockServer.chat(payload);
    return request.post<ChatResponse>('/rag/chat', payload);
  },

  /**
   * SSE 流式问答
   * 使用 fetch + ReadableStream 手动解析 SSE，以便携带 Authorization 头
   * （原生 EventSource 无法自定义请求头）
   * @returns 取消函数
   */
  chatStream(payload: ChatRequest, onEvent: (e: StreamEvent) => void): () => void {
    if (USE_MOCK) return mockServer.chatStream(payload, onEvent);

    const controller = new AbortController();

    const run = async () => {
      try {
        const resp = await fetch(`${API_BASE_URL}/rag/chat/stream`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
            ...authHeaders(),
          },
          body: JSON.stringify(payload),
          signal: controller.signal,
        });

        if (!resp.ok || !resp.body) {
          onEvent({ type: 'error', message: `流式请求失败：HTTP ${resp.status}` });
          return;
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        // 逐块读取并按 SSE 空行分帧
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          const frames = buffer.split('\n\n');
          // 最后一段可能不完整，留待下一轮拼接
          buffer = frames.pop() ?? '';

          for (const frame of frames) {
            const parsed = parseSseFrame(frame);
            if (parsed) onEvent(parsed);
          }
        }

        // 处理残余帧
        if (buffer.trim()) {
          const parsed = parseSseFrame(buffer);
          if (parsed) onEvent(parsed);
        }
      } catch (err) {
        // 主动取消不算异常
        if ((err as Error).name === 'AbortError') return;
        onEvent({ type: 'error', message: (err as Error).message || '流式连接异常' });
      }
    };

    void run();
    return () => controller.abort();
  },

  /** 上传文档并触发索引，含分块参数 */
  upload(
    file: File,
    options: UploadOptions,
    onProgress?: (percent: number) => void,
  ): Promise<DocumentItem> {
    if (USE_MOCK) {
      // mock 场景下模拟进度递增
      if (onProgress) {
        let p = 0;
        const timer = window.setInterval(() => {
          p = Math.min(96, p + 12);
          onProgress(p);
          if (p >= 96) window.clearInterval(timer);
        }, 120);
      }
      return mockServer.upload(file, options);
    }

    const form = new FormData();
    form.append('file', file);
    form.append('kbId', String(options.kbId));
    // 后端按分块策略名区分：text-model / hierarchical-model
    form.append('chunkStrategy', options.chunkStrategy);
    form.append('chunkSize', String(options.chunkSize));
    form.append('chunkOverlap', String(options.chunkOverlap));
    return request.upload<DocumentItem>('/rag/upload', form, onProgress);
  },

  /** 单知识库检索 */
  search(payload: SearchRequest): Promise<SearchHit[]> {
    if (USE_MOCK) return mockServer.search(payload);
    return request.post<SearchHit[]>('/rag/search', payload);
  },

  /** 多知识库联合检索 */
  multiSearch(payload: MultiSearchRequest): Promise<SearchHit[]> {
    if (USE_MOCK) return mockServer.multiSearch(payload);
    return request.post<SearchHit[]>('/rag/multi-search', payload);
  },

  /** 文档列表 */
  documents(kbId?: number): Promise<DocumentItem[]> {
    if (USE_MOCK) return mockServer.listDocs(kbId);
    return request.get<DocumentItem[]>('/rag/documents', { params: kbId ? { kbId } : undefined });
  },
};

/**
 * 解析单个 SSE 帧
 * 支持 `event: xxx` + `data: {...}` 组合，事件类型涵盖 citations/content/done/error
 */
function parseSseFrame(frame: string): StreamEvent | null {
  const lines = frame.split('\n').map((l) => l.trim());
  let event = '';
  const dataLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
  }

  const raw = dataLines.join('\n');
  if (!event && !raw) return null;

  // 兼容 [DONE] 终止标记
  if (raw === '[DONE]' || event === 'done') return { type: 'done' };

  let payload: Record<string, unknown> = {};
  if (raw) {
    try {
      payload = JSON.parse(raw) as Record<string, unknown>;
    } catch {
      // 非 JSON 时视为纯文本增量
      return { type: 'content', content: raw };
    }
  }

  switch (event) {
    case 'citations':
      return {
        type: 'citations',
        citations: (payload.citations ?? payload) as Citation[],
      };
    case 'content':
      return { type: 'content', content: String(payload.content ?? '') };
    case 'error':
      return { type: 'error', message: String(payload.message ?? '生成失败') };
    default:
      // 未声明 event 时按字段推断
      if (payload.citations) return { type: 'citations', citations: payload.citations as Citation[] };
      if (payload.content !== undefined) return { type: 'content', content: String(payload.content) };
      if (payload.error) return { type: 'error', message: String(payload.error) };
      return null;
  }
}
