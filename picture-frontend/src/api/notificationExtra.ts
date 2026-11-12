// 手动补充的站内通知接口封装。
// 生成式客户端（*Controller.ts）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

/** 通知类型，与后端 NotificationTypeEnum 保持一致 */
export type NotificationType =
  'REVIEW_PASS' | 'REVIEW_REJECT' | 'PASSWORD_RESET' | 'ROLE_CHANGED' | 'SYSTEM'

export interface NotificationItem {
  id?: string
  type?: NotificationType
  typeText?: string
  title?: string
  content?: string
  bizType?: 'PICTURE' | 'USER' | 'SYSTEM'
  bizId?: string
  isRead?: number
  createTime?: string
}

interface BaseResponseNotificationPage {
  code?: number
  data?: {
    records?: NotificationItem[]
    total?: number
    current?: number
    pageSize?: number
    pages?: number
  }
  message?: string
}

interface BaseResponseCount {
  code?: number
  data?: { count?: number }
  message?: string
}

interface BaseResponseUpdated {
  code?: number
  data?: { updated?: number; deleted?: number; sent?: number }
  message?: string
}

/**
 * 我的通知分页
 */
export async function listNotifications(
  params: { current?: number; pageSize?: number; onlyUnread?: boolean },
  options?: { [key: string]: any },
) {
  return request<BaseResponseNotificationPage>('/notification/page', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}

/**
 * 未读数（顶栏红点轮询）
 */
export async function getUnreadNotificationCount(options?: { [key: string]: any }) {
  return request<BaseResponseCount>('/notification/unread-count', {
    method: 'GET',
    ...(options || {}),
  })
}

/**
 * 批量标记已读
 */
export async function markNotificationsRead(ids: string[], options?: { [key: string]: any }) {
  return request<BaseResponseUpdated>('/notification/read', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { ids },
    ...(options || {}),
  })
}

/**
 * 全部标记已读
 */
export async function markAllNotificationsRead(options?: { [key: string]: any }) {
  return request<BaseResponseUpdated>('/notification/read/all', {
    method: 'POST',
    ...(options || {}),
  })
}

/**
 * 批量删除通知
 */
export async function deleteNotifications(ids: string[], options?: { [key: string]: any }) {
  return request<BaseResponseUpdated>('/notification/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { ids },
    ...(options || {}),
  })
}

/**
 * 发送系统公告（管理员）
 */
export async function broadcastNotification(
  body: { title: string; content: string },
  options?: { [key: string]: any },
) {
  return request<BaseResponseUpdated>('/notification/broadcast', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
