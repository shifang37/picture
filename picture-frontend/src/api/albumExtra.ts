// 手动补充的专辑接口封装。
// 生成式客户端（*Controller.ts）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

/** 专辑列表项 */
export interface AlbumVO {
  id?: string
  userId?: string
  name?: string
  description?: string
  coverPictureId?: string
  coverUrl?: string
  /** 作者视角的图片总数（含未过审） */
  pictureCount?: string | number
  /** 公开图片数量（仅已过审） */
  publicCount?: string | number
  isPublic?: number
  createTime?: string
  updateTime?: string
  user?: API.PublicUserVO
}

/** 专辑详情：额外返回当前访问者是否可以管理 */
export interface AlbumDetailVO extends AlbumVO {
  canManage?: boolean
}

export interface AlbumAddParams {
  name: string
  description?: string
  isPublic?: boolean
}

export interface AlbumUpdateParams {
  id: string
  name?: string
  description?: string
  isPublic?: number
  coverPictureId?: string
}

interface BaseResponseAlbumVO {
  code?: number
  data?: AlbumVO
  message?: string
}

interface BaseResponseAlbumDetailVO {
  code?: number
  data?: AlbumDetailVO
  message?: string
}

interface BaseResponsePageAlbumVO {
  code?: number
  data?: {
    records?: AlbumVO[]
    total?: number
    current?: number
    size?: number
  }
  message?: string
}

interface BaseResponsePagePictureVO {
  code?: number
  data?: API.PagePictureVO
  message?: string
}

/**
 * 创建专辑（默认私密）
 */
export async function addAlbum(body: AlbumAddParams, options?: { [key: string]: any }) {
  return request<BaseResponseAlbumVO>('/album/add', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

/**
 * 更新专辑（字段为空表示不修改）
 */
export async function updateAlbum(body: AlbumUpdateParams, options?: { [key: string]: any }) {
  return request<BaseResponseAlbumVO>('/album/update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

/**
 * 删除专辑（不影响图片本身）
 */
export async function deleteAlbum(id: string, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/album/delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { id },
    ...(options || {}),
  })
}

/**
 * 获取专辑详情（私密专辑仅作者/管理员可见）
 */
export async function getAlbum(id: string, options?: { [key: string]: any }) {
  return request<BaseResponseAlbumDetailVO>('/album/get', {
    method: 'GET',
    params: { id },
    ...(options || {}),
  })
}

/**
 * 我的专辑分页（含私密）
 */
export async function listMyAlbumPage(
  body: { current?: number; pageSize?: number },
  options?: { [key: string]: any },
) {
  return request<BaseResponsePageAlbumVO>('/album/my/page', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    ...(options || {}),
  })
}

/**
 * 公开专辑分页；userId 为空时返回全站公开专辑
 */
export async function listPublicAlbumPage(
  params: { userId?: string; current?: number; pageSize?: number },
  options?: { [key: string]: any },
) {
  return request<BaseResponsePageAlbumVO>('/album/public/page', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}

/**
 * 批量加入图片（幂等，重复自动跳过）
 */
export async function addAlbumPictures(
  albumId: string,
  pictureIds: string[],
  options?: { [key: string]: any },
) {
  return request<BaseResponseAlbumVO>('/album/picture/add', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { albumId, pictureIds },
    ...(options || {}),
  })
}

/**
 * 批量移除图片（不删除图片本身）
 */
export async function removeAlbumPictures(
  albumId: string,
  pictureIds: string[],
  options?: { [key: string]: any },
) {
  return request<BaseResponseAlbumVO>('/album/picture/remove', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { albumId, pictureIds },
    ...(options || {}),
  })
}

/**
 * 专辑内图片分页（公开访问只返回已过审图片）
 */
export async function listAlbumPicturePage(
  params: { albumId: string; current?: number; pageSize?: number },
  options?: { [key: string]: any },
) {
  return request<BaseResponsePagePictureVO>('/album/picture/page', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}
