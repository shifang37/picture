// 标签/分类字典接口封装（管理端）
import request from '@/request'

export type TagCategoryType = 'tag' | 'category'

export interface TagCategoryItem {
  id?: string
  type?: string
  name?: string
  createTime?: string
}

export interface TagCategoryAddParams {
  type: TagCategoryType
  name: string
}

export interface TagCategoryListResponse {
  code?: number
  data?: TagCategoryItem[]
  message?: string
}

/**
 * 管理员新增标签/分类
 */
export async function addTagCategory(body: TagCategoryAddParams, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/tag_category/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/**
 * 管理员删除标签/分类
 */
export async function deleteTagCategory(id: string, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/tag_category/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { id },
    ...(options || {}),
  })
}

/**
 * 管理端字典列表（带 id）
 */
export async function listTagCategoryAdmin(options?: { [key: string]: any }) {
  return request<TagCategoryListResponse>('/tag_category/list', {
    method: 'GET',
    ...(options || {}),
  })
}

/**
 * 从现有已通过审核的图片同步标签/分类，返回新增数量
 */
export async function syncTagCategory(options?: { [key: string]: any }) {
  return request<{ code?: number; data?: number; message?: string }>('/tag_category/sync', {
    method: 'POST',
    ...(options || {}),
  })
}
