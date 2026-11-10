import { saveAs } from 'file-saver'

/**
 * 格式化文件大小
 * 后端将 Long 序列化为字符串，因此这里同时接受 number 和 string
 * @param size
 */
export const formatSize = (size?: number | string) => {
  if (size === undefined || size === null || size === '') return '未知'
  const value = Number(size)
  if (!value || Number.isNaN(value)) return '未知'
  if (value < 1024) return value + ' B'
  if (value < 1024 * 1024) return (value / 1024).toFixed(2) + ' KB'
  return (value / (1024 * 1024)).toFixed(2) + ' MB'
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
