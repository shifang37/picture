// 手动补充的收藏/点赞接口封装。
// 生成式客户端（*Controller.ts）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

/** 互动类型：收藏 / 点赞 */
export type InteractionType = 'favorite' | 'like'

/** 某张图片的互动状态与计数 */
export interface InteractionStatus {
  pictureId?: string
  favorite?: boolean
  like?: boolean
  favoriteCount?: string | number
  likeCount?: string | number
}

interface BaseResponseInteractionStatus {
  code?: number
  data?: InteractionStatus
  message?: string
}

interface BaseResponsePagePictureVO {
  code?: number
  data?: API.PagePictureVO
  message?: string
}

/**
 * 收藏 / 点赞（幂等：已存在时直接返回当前状态）
 */
export async function addInteraction(
  body: { pictureId: string; type: InteractionType },
  options?: { [key: string]: any },
) {
  return request<BaseResponseInteractionStatus>('/interaction/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/**
 * 取消收藏 / 取消点赞（幂等）
 */
export async function removeInteraction(
  body: { pictureId: string; type: InteractionType },
  options?: { [key: string]: any },
) {
  return request<BaseResponseInteractionStatus>('/interaction/remove', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/**
 * 查询互动状态与计数（匿名可用，未登录时 favorite/like 恒为 false）
 */
export async function getInteractionStatus(pictureId: string, options?: { [key: string]: any }) {
  return request<BaseResponseInteractionStatus>('/interaction/status', {
    method: 'GET',
    params: { pictureId },
    ...(options || {}),
  })
}

/**
 * 我的收藏 / 我的点赞（只返回已过审图片，按互动时间倒序）
 */
export async function listMyInteractionPage(
  body: { type: InteractionType; current?: number; pageSize?: number },
  options?: { [key: string]: any },
) {
  return request<BaseResponsePagePictureVO>('/interaction/my/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
