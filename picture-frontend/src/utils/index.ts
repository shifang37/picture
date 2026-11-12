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
 * <p>
 * 必须走后端流式代理：同源请求不依赖 COS 桶的跨域配置，后端会在文件完整写完后
 * 按「用户/IP + 图片 + 天」去重并自增下载次数。前端不再单独调用计数接口。
 * @param id 图片 id
 */
export function downloadPicture(id?: string) {
  if (!id) {
    return
  }
  const link = document.createElement('a')
  link.href = `/api/picture/download/${encodeURIComponent(id)}`
  link.rel = 'noopener'
  // 后端返回 Content-Disposition: attachment，浏览器会直接保存而不会跳转页面
  document.body.appendChild(link)
  link.click()
  link.remove()
}
