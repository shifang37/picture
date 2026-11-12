// 手动补充的配额接口封装。
// 后端把 Long 序列化为字符串，前端统一用 Number() 转换后再计算。
import request from '@/request'

export interface UserQuota {
  storageUsed?: string | number
  storageQuota?: string | number
  pictureUsed?: number
  pictureQuota?: number
  /** 是否不受配额限制（管理员或全局关闭配额） */
  exempt?: boolean
}

interface BaseResponseUserQuota {
  code?: number
  data?: UserQuota
  message?: string
}

/**
 * 查询当前登录用户的配额
 */
export async function getMyQuota(options?: { [key: string]: any }) {
  return request<BaseResponseUserQuota>('/user/quota', {
    method: 'GET',
    ...(options || {}),
  })
}

export interface UserQuotaUpdateParams {
  userId: string
  /** 存储配额（字节）；不传表示不修改 */
  storageQuota?: number
  /** 图片数量上限；不传表示不修改 */
  pictureQuota?: number
}

/**
 * 管理员调整指定用户配额
 */
export async function updateUserQuota(
  body: UserQuotaUpdateParams,
  options?: { [key: string]: any },
) {
  return request<BaseResponseUserQuota>('/user/quota/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/**
 * 管理员按图片表重算已用配额；不传 userId 时全量重算
 */
export async function recalcUserQuota(userId?: string, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: number; message?: string }>('/user/quota/recalc', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: userId ? { userId } : {},
    ...(options || {}),
  })
}
