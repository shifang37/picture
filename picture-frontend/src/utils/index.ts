import { saveAs } from 'file-saver'

/**
 * 格式化文件大小
 * 后端将 Long 序列化为字符串，因此这里同时接受 number 和 string
 * @param size
 */
export const formatSize = (size?: number | string) => {
  if (size === undefined || size === null || size === '') return '未知'
  const value = Number(size)
  if (Number.isNaN(value)) return '未知'
  if (value < 1024) return value + ' B'
  if (value < 1024 * 1024) return (value / 1024).toFixed(2) + ' KB'
  return (value / (1024 * 1024)).toFixed(2) + ' MB'
}

/**
 * 生成缩略图地址（依赖腾讯云 COS 图片处理参数）
 * @param url 原图地址
 * @param size 缩略图最长边
 */
export function getThumbnailUrl(url?: string, size = 300) {
  if (!url) {
    return ''
  }
  // 已经带图片处理参数时不重复拼接
  if (url.includes('imageMogr2')) {
    return url
  }
  // 缩略图同时转 WebP 并压缩质量，进一步减少列表页流量
  return `${url}${url.includes('?') ? '&' : '?'}imageMogr2/thumbnail/${size}x/format/webp/quality/80`
}

/**
 * 下载图片
 * @param url 图片下载地址
 * @param fileName 要保存为的文件名
 */
export function downloadImage(url?: string, fileName?: string) {
  if (!url) {
    return
  }
  saveAs(url, fileName)
}
