// 管理端运维接口封装：搜索索引重建、互动计数与专辑计数重算。
// 三个接口都只返回「处理了多少条」，因此统一用同一个响应类型。
import request from '@/request'

interface AdminOpsResponse {
  code?: number
  data?: number
  message?: string
}

/**
 * 按 MySQL 全量重建公开图片索引（仅管理员，搜索未启用时后端会返回业务错误）
 */
export async function reindexSearch(options?: { [key: string]: any }) {
  return request<AdminOpsResponse>('/search/reindex', {
    method: 'POST',
    ...(options || {}),
  })
}

/**
 * 按关系表重算收藏数 / 点赞数；不传 pictureId 时全量分批重算（仅管理员）
 */
export async function recountInteractions(pictureId?: string, options?: { [key: string]: any }) {
  return request<AdminOpsResponse>('/interaction/recount', {
    method: 'POST',
    params: pictureId ? { pictureId } : undefined,
    ...(options || {}),
  })
}

/**
 * 按关系表重算专辑图片数量；不传 albumId 时全量分批重算（仅管理员）
 */
export async function recountAlbums(albumId?: string, options?: { [key: string]: any }) {
  return request<AdminOpsResponse>('/album/recount', {
    method: 'POST',
    params: albumId ? { albumId } : undefined,
    ...(options || {}),
  })
}
