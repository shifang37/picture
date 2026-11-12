/**
 * 浏览器端图片压缩：长边超过 1920 或体积超过 2MB 时用 Canvas 重新编码。
 *
 * 约定：
 * - PNG 只缩放、保留透明通道；WebP 交给浏览器编码；其余格式输出 JPEG；
 * - JPEG 体积仍超限时按 0.85 → 0.70 → 0.55 → 0.40 逐级降质量；
 * - 任何一步失败都回退原文件，压缩只是优化，不能阻断上传。
 */
import { formatBytes } from '@/utils/size'

export const DEFAULT_MAX_SIZE_MB = 2

export const DEFAULT_MAX_DIMENSION = 1920

export const DEFAULT_JPEG_QUALITY = 0.85

/**
 * JPEG 质量阶梯：体积仍超目标时依次尝试
 */
const QUALITY_STEPS = [0.85, 0.7, 0.55, 0.4]

export interface CompressImageOptions {
  /** 目标体积上限（MB），默认 2 */
  maxSizeMB?: number
  /** 长边上限，默认 1920 */
  maxWidthOrHeight?: number
  /** 首次编码质量，默认 0.85 */
  quality?: number
}

export interface ImageDimensions {
  width: number
  height: number
}

export interface CompressImageResult {
  /** 压缩后的文件；无需压缩时为原文件 */
  file: File
  /** 是否真的生成了新文件 */
  compressed: boolean
  originalSize: number
  size: number
}

/**
 * 计算缩放后的目标尺寸：只缩不放
 */
export function computeTargetSize(
  width: number,
  height: number,
  maxDimension = DEFAULT_MAX_DIMENSION,
): ImageDimensions {
  if (width <= 0 || height <= 0) {
    return { width: Math.max(1, width), height: Math.max(1, height) }
  }
  const longSide = Math.max(width, height)
  if (longSide <= maxDimension) {
    return { width, height }
  }
  const scale = maxDimension / longSide
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale)),
  }
}

/**
 * 是否需要压缩：体积超限或长边超限
 */
export function shouldCompress(
  size: number,
  dimensions: ImageDimensions | null | undefined,
  options: CompressImageOptions = {},
): boolean {
  const maxBytes = (options.maxSizeMB ?? DEFAULT_MAX_SIZE_MB) * 1024 * 1024
  if (size > maxBytes) {
    return true
  }
  if (!dimensions) {
    return false
  }
  return Math.max(dimensions.width, dimensions.height) > (options.maxWidthOrHeight ?? DEFAULT_MAX_DIMENSION)
}

/**
 * 压缩提示文案：3.2MB → 1.8MB
 */
export function describeCompression(result: CompressImageResult): string {
  return `${formatBytes(result.originalSize)} → ${formatBytes(result.size)}`
}

/**
 * 压缩图片；非图片、无需压缩或压缩失败时原样返回
 */
export async function compressImage(
  file: File,
  options: CompressImageOptions = {},
): Promise<CompressImageResult> {
  const originalSize = file.size
  const passthrough: CompressImageResult = { file, compressed: false, originalSize, size: originalSize }
  if (!file.type.startsWith('image/') || typeof document === 'undefined') {
    return passthrough
  }
  let dimensions: ImageDimensions | null = null
  try {
    dimensions = await readImageDimensions(file)
  } catch {
    return passthrough
  }
  if (!shouldCompress(originalSize, dimensions, options)) {
    return passthrough
  }
  const maxDimension = options.maxWidthOrHeight ?? DEFAULT_MAX_DIMENSION
  const target = computeTargetSize(dimensions.width, dimensions.height, maxDimension)
  const maxBytes = (options.maxSizeMB ?? DEFAULT_MAX_SIZE_MB) * 1024 * 1024
  try {
    const blob = await renderToBlob(file, target, options.quality ?? DEFAULT_JPEG_QUALITY, maxBytes)
    if (!blob || blob.size >= originalSize) {
      return passthrough
    }
    const type = blob.type || file.type
    const compressedFile = new File([blob], replaceExtension(file.name, extensionFor(type)), {
      type,
      lastModified: Date.now(),
    })
    return {
      file: compressedFile,
      compressed: true,
      originalSize,
      size: compressedFile.size,
    }
  } catch {
    return passthrough
  }
}

/**
 * 读取图片宽高；优先用 createImageBitmap（支持 HEIC 的浏览器可复用）
 */
export async function readImageDimensions(file: File): Promise<ImageDimensions> {
  const source = await loadImageSource(file)
  try {
    return { width: source.width, height: source.height }
  } finally {
    source.close()
  }
}

interface ImageSource {
  source: CanvasImageSource
  width: number
  height: number
  close: () => void
}

async function loadImageSource(file: File): Promise<ImageSource> {
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(file)
    return {
      source: bitmap,
      width: bitmap.width,
      height: bitmap.height,
      close: () => bitmap.close(),
    }
  }
  const url = URL.createObjectURL(file)
  try {
    const image = await loadHtmlImage(url)
    return {
      source: image,
      width: image.naturalWidth || image.width,
      height: image.naturalHeight || image.height,
      close: () => URL.revokeObjectURL(url),
    }
  } catch (error) {
    URL.revokeObjectURL(url)
    throw error
  }
}

function loadHtmlImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('图片加载失败'))
    image.src = url
  })
}

/**
 * 把图片绘制到 Canvas 并按目标格式编码；JPEG 体积仍超限时逐级降质量
 */
async function renderToBlob(
  file: File,
  target: ImageDimensions,
  quality: number,
  maxBytes: number,
): Promise<Blob | null> {
  const image = await loadImageSource(file)
  try {
    const canvas = document.createElement('canvas')
    canvas.width = target.width
    canvas.height = target.height
    const context = canvas.getContext('2d')
    if (!context) {
      return null
    }
    context.imageSmoothingEnabled = true
    context.imageSmoothingQuality = 'high'
    context.drawImage(image.source, 0, 0, target.width, target.height)
    return await encodeCanvas(canvas, outputType(file.type), quality, maxBytes)
  } finally {
    image.close()
  }
}

async function encodeCanvas(
  canvas: HTMLCanvasElement,
  type: string,
  quality: number,
  maxBytes: number,
): Promise<Blob | null> {
  let blob = await canvasToBlob(canvas, type, quality)
  if (!blob || type === 'image/png') {
    return blob
  }
  for (const step of QUALITY_STEPS) {
    if (blob.size <= maxBytes) {
      break
    }
    blob = await canvasToBlob(canvas, type, Math.min(step, quality))
  }
  return blob
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => {
        if (blob) {
          resolve(blob)
        } else {
          reject(new Error('图片编码失败'))
        }
      },
      type,
      quality,
    )
  })
}

/**
 * 输出格式：PNG 保留透明；浏览器支持时保留 WebP；其余统一 JPEG
 */
function outputType(inputType: string): string {
  if (inputType === 'image/png') {
    return 'image/png'
  }
  if (inputType === 'image/webp') {
    return 'image/webp'
  }
  return 'image/jpeg'
}

function extensionFor(type: string): string {
  if (type === 'image/png') {
    return 'png'
  }
  if (type === 'image/webp') {
    return 'webp'
  }
  return 'jpg'
}

function replaceExtension(name: string, extension: string): string {
  const dotIndex = name.lastIndexOf('.')
  const baseName = dotIndex > 0 ? name.slice(0, dotIndex) : name
  return `${baseName}.${extension}`
}
