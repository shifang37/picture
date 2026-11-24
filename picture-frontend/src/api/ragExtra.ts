// 手动补充的 RAG 接口封装。
// 生成式客户端由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

/** 前端可见的 RAG 开关（GET /rag/config，公开） */
export interface RagConfig {
  /** RAG 总开关 */
  enabled?: boolean
  /** 是否可用混合检索（RAG + Elasticsearch 同时开启） */
  hybridSearch?: boolean
  /** 是否可用图库问答 */
  askEnabled?: boolean
  /** 每人每日问答次数上限 */
  askDailyLimit?: number
  /** 单次问答默认候选数 */
  askTopK?: number
}

/** 问答引用：只包含公开图片字段，reason 由后端按分类/标签/尺寸生成 */
export interface RagCitation {
  id?: string
  name?: string
  pictureUrl?: string
  thumbnailUrl?: string
  reason?: string
}

/** 问答三态：正常回答 / 拒答 / 降级 */
export interface RagAskResult {
  answer?: string
  citations?: RagCitation[]
  refused?: boolean
  degraded?: boolean
}

/** 管理端向量索引状态（GET /rag/status） */
export interface RagStatus {
  enabled?: boolean
  textModel?: string
  imageModel?: string
  dims?: number
  indexName?: string
  alias?: string
  version?: string
  textVersion?: string
  docCount?: number | string
  pendingCount?: number | string
  failedCount?: number | string
  stale?: boolean
}

/** 全量重建向量索引结果（POST /rag/reindex） */
export interface RagReindexResult {
  indexed?: number
  skipped?: number
  failed?: number
  switched?: boolean
  message?: string
}

/**
 * 读取 RAG 前端开关（公开）。
 * <p>
 * 首页据此决定是否传 `searchMode=hybrid`，问答页与顶栏据此决定是否展示入口；
 * 关闭 RAG 时接口同样返回 200，所有能力标记为 false。
 */
export async function fetchRagConfig(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: RagConfig; message?: string }>('/rag/config', {
    method: 'GET',
    // 首页首屏会等它：后端异常时不要让搜索被 60s 默认超时拖住
    timeout: 5000,
    ...(options || {}),
  })
}

/**
 * 图库问答（需登录，按用户每日限额）。
 * <p>
 * 业务上只有三种状态：正常回答、拒答（refused=true）、降级（degraded=true）；
 * 超限时返回 42900，前端应提示「今日次数已用完」而不是通用错误。
 */
export async function askRag(question: string, topK?: number, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: RagAskResult; message?: string }>('/rag/ask', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
    },
    data: topK ? { question, topK } : { question },
    ...(options || {}),
  })
}

/** 读取向量索引状态（仅管理员） */
export async function fetchRagStatus(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: RagStatus; message?: string }>('/rag/status', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 全量重建向量索引（仅管理员，同步执行，耗时随图片数量增长） */
export async function reindexRag(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: RagReindexResult; message?: string }>('/rag/reindex', {
    method: 'POST',
    ...(options || {}),
  })
}
