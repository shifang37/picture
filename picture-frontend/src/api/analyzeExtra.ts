// 管理端数据看板接口封装。
// 后端把 Long 序列化为字符串，因此数量字段同时兼容 number / string。
import request from '@/request'

export interface AnalyzeOverview {
  userCount?: number | string
  pictureCount?: number | string
  pendingReviewCount?: number | string
  totalPicSize?: number | string
  totalDownloadCount?: number | string
}

export interface AnalyzeUploadTrendItem {
  /** yyyy-MM-dd */
  date?: string
  count?: number | string
}

export interface AnalyzeCategoryItem {
  category?: string
  count?: number | string
  totalSize?: number | string
}

export interface AnalyzeTagItem {
  tag?: string
  count?: number | string
}

export interface AnalyzeReview {
  approvedCount?: number | string
  rejectedCount?: number | string
  averageReviewSeconds?: number | string
}

export interface AnalyzeTopDownloadItem {
  id?: string
  name?: string
  url?: string
  downloadCount?: number | string
}

interface AnalyzeResponse<T> {
  code?: number
  data?: T
  message?: string
}

/**
 * 核心指标概览
 */
export async function getAnalyzeOverview(options?: { [key: string]: any }) {
  return request<AnalyzeResponse<AnalyzeOverview>>('/analyze/overview', {
    method: 'GET',
    ...(options || {}),
  })
}

/**
 * 最近 N 天的每日上传数量
 */
export async function getAnalyzeUploadTrend(days = 30, options?: { [key: string]: any }) {
  return request<AnalyzeResponse<AnalyzeUploadTrendItem[]>>('/analyze/upload_trend', {
    method: 'GET',
    params: { days },
    ...(options || {}),
  })
}

/**
 * 各分类的图片数量与体积
 */
export async function getAnalyzeCategory(options?: { [key: string]: any }) {
  return request<AnalyzeResponse<AnalyzeCategoryItem[]>>('/analyze/category', {
    method: 'GET',
    ...(options || {}),
  })
}

/**
 * 使用次数最多的标签
 */
export async function getAnalyzeTags(limit = 20, options?: { [key: string]: any }) {
  return request<AnalyzeResponse<AnalyzeTagItem[]>>('/analyze/tags', {
    method: 'GET',
    params: { limit },
    ...(options || {}),
  })
}

/**
 * 审核统计
 */
export async function getAnalyzeReview(options?: { [key: string]: any }) {
  return request<AnalyzeResponse<AnalyzeReview>>('/analyze/review', {
    method: 'GET',
    ...(options || {}),
  })
}

/**
 * 下载次数排行
 */
export async function getAnalyzeTopDownload(limit = 10, options?: { [key: string]: any }) {
  return request<AnalyzeResponse<AnalyzeTopDownloadItem[]>>('/analyze/top_download', {
    method: 'GET',
    params: { limit },
    ...(options || {}),
  })
}
