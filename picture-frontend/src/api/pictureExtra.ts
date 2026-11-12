// 手动补充的图片接口封装。
// 生成式客户端（pictureController.ts）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

export interface PictureReviewBatchResult {
  successCount?: number
  failCount?: number
  errors?: string[]
}

export interface PictureReviewBatchParams {
  ids: string[]
  reviewStatus: number
  reviewMessage?: string
}

/**
 * 批量审核图片（管理员）
 */
export async function batchReviewPicture(
  body: PictureReviewBatchParams,
  options?: { [key: string]: any },
) {
  return request<{ code?: number; data?: PictureReviewBatchResult; message?: string }>(
    '/picture/review/batch',
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      data: body,
      ...(options || {}),
    },
  )
}

export interface PictureReviewLog {
  id?: string
  pictureId?: string
  reviewerId?: string
  reviewerName?: string
  reviewerAccount?: string
  reviewStatus?: number
  reviewMessage?: string
  createTime?: string
}

export interface PictureAiSuggestion {
  pictureId?: string
  name?: string
  introduction?: string
  category?: string
  tags?: string[]
  model?: string
  status?: number
  createTime?: string
}

/**
 * 查询图片的 AI 建议（仅作者和管理员）
 */
export async function getPictureAiSuggestion(id: string, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: PictureAiSuggestion | null; message?: string }>(
    '/picture/ai_suggestion',
    {
      method: 'GET',
      params: {
        id,
      },
      ...(options || {}),
    },
  )
}

/**
 * 查询某张图片的审核历史（管理员）
 */
export async function listPictureReviewLogs(pictureId: string, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: PictureReviewLog[]; message?: string }>(
    '/picture/review/log',
    {
      method: 'GET',
      params: {
        pictureId,
      },
      ...(options || {}),
    },
  )
}

/**
 * 分页获取当前登录用户的图片（含待审核 / 已拒绝）
 */
export async function listMyPictureVoByPage(
  body: API.PictureQueryRequest,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponsePagePictureVO>('/picture/my/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/**
 * 上传图片（multipart/form-data）
 * 后端接收 file 图片文件，id 可选（重新上传已有图片时传）
 */
export async function uploadPicture(
  file: File,
  pictureUploadRequest?: API.PictureUploadRequest,
  options?: { [key: string]: any },
) {
  const formData = new FormData()
  formData.append('file', file)
  if (pictureUploadRequest?.id) {
    formData.append('id', pictureUploadRequest.id)
  }
  return request<API.BaseResponsePictureVO>('/picture/upload', {
    method: 'POST',
    data: formData,
    // 不要手动设置 Content-Type，浏览器会自动补上带 boundary 的 multipart 头
    ...(options || {}),
  })
}
