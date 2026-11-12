/**
 * 上传前的统一处理：格式/大小校验 → HEIC 转 JPEG → 压缩。
 * 单张上传与批量上传共用，保证两条链路的提示与目标体积一致。
 */
import { compressImage, type CompressImageOptions, type CompressImageResult } from '@/utils/image'
import { convertHeicToJpeg, isHeicFile } from '@/utils/heic'

/**
 * 服务端单张上限，与 app.upload.max-file-size-bytes 保持一致
 */
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

/**
 * 前端仍以 2MB 为目标压缩
 */
export const TARGET_UPLOAD_BYTES = 2 * 1024 * 1024

export const ALLOWED_EXTENSIONS = ['jpg', 'jpeg', 'png', 'webp', 'heic', 'heif']

export const ALLOWED_MIME_TYPES = [
  'image/jpeg',
  'image/jpg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif',
]

export type PrepareStage = 'converting' | 'compressing'

export interface PrepareImageOptions extends CompressImageOptions {
  /**
   * 阶段回调：页面据此显示“HEIC 转换中 / 压缩中”
   */
  onStage?: (stage: PrepareStage) => void
}

export interface PreparedImage {
  /** 最终要上传的文件 */
  file: File
  /** 原始文件体积 */
  originalSize: number
  /** 是否做过 HEIC 转换 */
  converted: boolean
  /** 压缩详情；未压缩时为 null */
  compression: CompressImageResult | null
}

/**
 * 校验文件后缀与 MIME：两部分都通过才继续
 * <p>
 * 部分浏览器拿不到 MIME（空），此时只按后缀判断，最终以后端文件头校验为准。
 */
export function validateImageFile(file: File): string | null {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXTENSIONS.includes(extension)) {
    return '仅支持 jpg、jpeg、png、webp、heic 格式的图片'
  }
  const typeAllowed = !file.type || ALLOWED_MIME_TYPES.includes(file.type.toLowerCase())
  if (!typeAllowed) {
    return '仅支持 jpg、jpeg、png、webp、heic 格式的图片'
  }
  if (!file.size) {
    return '不能上传空文件'
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return `不能上传超过 ${MAX_UPLOAD_BYTES / 1024 / 1024}MB 的图片`
  }
  return null
}

/**
 * 上传前处理：HEIC → JPEG，再按需压缩
 */
export async function prepareImageForUpload(
  file: File,
  options: PrepareImageOptions = {},
): Promise<PreparedImage> {
  const originalSize = file.size
  let current = file
  let converted = false
  if (isHeicFile(current)) {
    options.onStage?.('converting')
    current = await convertHeicToJpeg(current)
    converted = true
  }
  options.onStage?.('compressing')
  const compression = await compressImage(current, options)
  return {
    file: compression.file,
    originalSize,
    converted,
    compression: compression.compressed ? compression : null,
  }
}
