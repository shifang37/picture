/**
 * HEIC / HEIF 转换：iPhone 默认相册格式，浏览器无法直接解码。
 * <p>
 * heic2any 体积较大（约 1MB），只在真正选择 HEIC 文件时动态加载，不进入首屏 chunk。
 */
const HEIC_EXTENSIONS = ['heic', 'heif']

const HEIC_MIME_TYPES = ['image/heic', 'image/heif', 'image/heic-sequence', 'image/heif-sequence']

type HeicConverter = (options: {
  blob: Blob
  toType?: string
  quality?: number
  multiple?: boolean
}) => Promise<Blob | Blob[]>

/**
 * 是否为 HEIC / HEIF 文件（后缀或 MIME 任一命中）
 */
export function isHeicFile(file: File): boolean {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (HEIC_EXTENSIONS.includes(extension)) {
    return true
  }
  return HEIC_MIME_TYPES.includes((file.type || '').toLowerCase())
}

/**
 * 把 HEIC 转成 JPEG；失败时抛出带引导信息的错误，由页面提示用户先转换
 */
export async function convertHeicToJpeg(file: File): Promise<File> {
  let converter: HeicConverter
  try {
    const module = await import('heic2any')
    converter = (module.default ?? module) as unknown as HeicConverter
  } catch {
    throw new Error('当前网络环境无法加载 HEIC 转换组件，请先把照片转换为 JPG 再上传')
  }
  let converted: Blob | Blob[]
  try {
    converted = await converter({ blob: file, toType: 'image/jpeg', quality: 0.9 })
  } catch {
    throw new Error('HEIC 转换失败，请确认照片未损坏，或先转换为 JPG 再上传')
  }
  const blob = Array.isArray(converted) ? converted[0] : converted
  if (!blob) {
    throw new Error('HEIC 转换失败，请先把照片转换为 JPG 再上传')
  }
  return new File([blob], replaceExtension(file.name), {
    type: 'image/jpeg',
    lastModified: Date.now(),
  })
}

function replaceExtension(name: string): string {
  const baseName = name.replace(/\.(heic|heif)$/i, '')
  return `${baseName || 'photo'}.jpg`
}
