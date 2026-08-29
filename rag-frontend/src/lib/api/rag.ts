import type {
  ChatRequest,
  ChatResponse,
  DocumentItem,
  MultiSearchRequest,
  SearchHit,
  SearchRequest,
  StreamEvent,
  UploadOptions,
} from '@/lib/types';
import {
  API_BASE_URL,
  authHeaders,
  http,
  request,
} from './client';
import {
  mapCitation,
  parseCitationsData,
  toDocStatus,
  type BackendChatAnswer,
  type BackendFileResult,
  type BackendStreamEvent,
} from './adapter';

/**
 * RAG 问答与检索接口
 * 真实后端端点（rag-bootstrap / RagController + ChatController）：
 *   POST /api/rag/chat（multipart: request JSON part + 可选 files）
 *   POST /api/rag/chat/stream（multipart, SSE）
 *   POST /api/rag/upload、/api/rag/upload/text-model、/api/rag/upload/hierarchical-model
 *   POST /api/rag/search（query params）
 *   POST /api/rag/multi-search（JSON body）
 *   GET  /api/rag/documents?kbId=
 */
export const ragApi = {
  /**
   * 同步问答
   * 后端为 multipart 接口，需以 form 提交，request 为 JSON 字符串
   */
  async chat(payload: ChatRequest): Promise<ChatResponse> {
    const form = new FormData();
    form.append(
      'request',
      new Blob([JSON.stringify(buildChatRequest(payload))], { type: 'application/json' }),
    );
    const resp = await http.post<BackendChatAnswer>(`${API_BASE_URL}/rag/chat`, form, {
      headers: { ...authHeaders() },
      timeout: 120000,
    });
    return {
      answer: resp.data.answer ?? '',
      citations: (resp.data.citations ?? []).map(mapCitation),
      model: resp.data.model ?? payload.model,
      tokenUsage: undefined,
      costMs: resp.data.elapsedMs,
      sessionId: resp.data.sessionId,
    };
  },

  /**
   * SSE 流式问答（multipart + fetch 手动解析）
   * 后端事件：{type, data}，data 为字符串。
   *  - content: data 为文本增量
   *  - citations: data 为引用 JSON 数组字符串
   *  - done: data 为完整回答
   *  - error: data 为错误信息
   * @returns 取消函数
   */
  chatStream(payload: ChatRequest, onEvent: (e: StreamEvent) => void): () => void {
    const controller = new AbortController();
    const form = new FormData();
    form.append(
      'request',
      new Blob([JSON.stringify(buildChatRequest(payload))], { type: 'application/json' }),
    );

    const run = async () => {
      try {
        const resp = await fetch(`${API_BASE_URL}/rag/chat/stream`, {
          method: 'POST',
          headers: {
            Accept: 'text/event-stream',
            ...authHeaders(),
          },
          body: form,
          signal: controller.signal,
        });

        if (!resp.ok || !resp.body) {
          onEvent({ type: 'error', message: `流式请求失败：HTTP ${resp.status}` });
          return;
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';

        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });

          // 按行解析 SSE（后端以换行分隔多个事件）
          const lines = buffer.split('\n');
          buffer = lines.pop() ?? '';

          for (const line of lines) {
            const evt = parseStreamLine(line);
            if (evt) onEvent(evt);
          }
        }

        if (buffer.trim()) {
          const evt = parseStreamLine(buffer);
          if (evt) onEvent(evt);
        }
      } catch (err) {
        if ((err as Error).name === 'AbortError') return;
        onEvent({ type: 'error', message: (err as Error).message || '流式连接异常' });
      }
    };

    void run();
    return () => controller.abort();
  },

  /**
   * 上传文档
   * 后端提供 /upload、/upload/text-model、/upload/hierarchical-model 三个端点，
   * 前端按所选分块策略路由到对应端点。
   */
  upload(
    file: File,
    options: UploadOptions,
    onProgress?: (percent: number) => void,
  ): Promise<DocumentItem> {
    const form = new FormData();
    form.append('file', file);
    form.append('kbId', String(options.kbId));

    const url =
      options.chunkStrategy === 'hierarchical-model'
        ? '/rag/upload/hierarchical-model'
        : '/rag/upload/text-model';

    return request
      .upload<BackendFileResult>(url, form, onProgress)
      .then((r) => toDocumentItem(r, file.name, options));
  },

  /**
   * 单知识库检索
   * 后端为 query params 接口
   */
  async search(payload: SearchRequest): Promise<SearchHit[]> {
    const params = new URLSearchParams();
    params.set('kbId', String(payload.kbId));
    params.set('query', payload.query);
    params.set('topK', String(payload.topK));
    // 后端枚举为大写：VECTOR_ONLY / BM25_ONLY / HYBRID
    params.set('searchMode', payload.retrievalMode.toUpperCase());
    const list = await request.get<BackendFileResult[]>(`/rag/search?${params.toString()}`);
    return list.map((r, i) => ({
      docId: Number(r.fileId ?? i),
      docName: r.fileName ?? '',
      kbId: payload.kbId,
      kbName: '',
      chunkIndex: 0,
      score: r.score ?? 0,
      content: r.snippet ?? '',
    }));
  },

  /** 多知识库联合检索 */
  async multiSearch(payload: MultiSearchRequest): Promise<SearchHit[]> {
    const list = await request.post<BackendFileResult[]>('/rag/multi-search', {
      kbIds: payload.kbIds,
      query: payload.query,
      topK: payload.topK,
      searchMode: payload.retrievalMode.toUpperCase(),
    });
    return list.map((r, i) => ({
      docId: Number(r.fileId ?? i),
      docName: r.fileName ?? '',
      kbId: payload.kbIds[0] ?? 0,
      kbName: '',
      chunkIndex: 0,
      score: r.score ?? 0,
      content: r.snippet ?? '',
    }));
  },

  /** 文档列表：后端要求必须传 kbId */
  documents(kbId: number): Promise<DocumentItem[]> {
    return request.get<BackendFileResult[]>(`/rag/documents?kbId=${kbId}`).then((list) =>
      list.map((r, i) => ({
        id: Number(r.fileId ?? i),
        fileName: r.fileName ?? '',
        fileType: '',
        fileSizeKb: 0,
        kbId,
        kbName: '',
        chunkCount: r.chunkCount ?? 0,
        version: Number(r.documentVersion ?? 1),
        status: toDocStatus(r.success),
        chunkStrategy: 'text-model',
        uploadedBy: '',
        uploadedAt: '',
        errorMsg: r.message,
      })),
    );
  },
};

/** 将前端 ChatRequest 转为后端 ChatRequest JSON（单 kbId + sessionId） */
function buildChatRequest(p: ChatRequest): Record<string, unknown> {
  return {
    kbId: p.kbIds[0] ?? null,
    query: p.question,
    sessionId: p.sessionId ?? null,
    model: p.model,
    stream: false,
  };
}

/** 解析单行 SSE 数据（后端事件 JSON 对象） */
function parseStreamLine(line: string): StreamEvent | null {
  const trimmed = line.trim();
  if (!trimmed) return null;
  // 忽略可能出现的 data: 前缀
  const payloadStr = trimmed.startsWith('data:') ? trimmed.slice(5).trim() : trimmed;
  if (!payloadStr || payloadStr === '[DONE]') return null;

  let evt: BackendStreamEvent;
  try {
    evt = JSON.parse(payloadStr) as BackendStreamEvent;
  } catch {
    // 非 JSON 视为纯文本内容
    return { type: 'content', content: trimmed };
  }

  switch (evt.type) {
    case 'citations':
      return { type: 'citations', citations: parseCitationsData(evt.data) };
    case 'content':
      return { type: 'content', content: evt.data ?? '' };
    case 'done':
      return { type: 'done' };
    case 'error':
      return { type: 'error', message: evt.data ?? '生成失败' };
    default:
      return null;
  }
}

/** 将后端上传结果转为前端文档行 */
function toDocumentItem(r: BackendFileResult, fileName: string, opts: UploadOptions): DocumentItem {
  const ext = fileName.split('.').pop()?.toLowerCase() ?? '';
  return {
    id: Number(r.fileId ?? Date.now()),
    fileName: r.fileName ?? fileName,
    fileType: ext,
    fileSizeKb: 0,
    kbId: opts.kbId,
    kbName: '',
    chunkCount: r.chunkCount ?? r.insertedCount ?? 0,
    version: Number(r.documentVersion ?? 1),
    status: toDocStatus(r.success),
    chunkStrategy: opts.chunkStrategy,
    uploadedBy: '',
    uploadedAt: new Date().toISOString(),
    errorMsg: r.message,
  };
}
