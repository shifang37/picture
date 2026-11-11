/**
 * 数据看板展示用的格式化工具。
 */

/**
 * 把后端返回的 number / string / null 统一转成有限数字。
 */
export function toNumber(value?: number | string | null): number {
  if (value === undefined || value === null || value === '') {
    return 0
  }
  const number = Number(value)
  return Number.isFinite(number) ? number : 0
}

/**
 * 千分位格式化数量，例如 12345 → 12,345。
 */
export function formatCount(value?: number | string | null): string {
  return new Intl.NumberFormat('zh-CN').format(toNumber(value))
}

/**
 * 把秒数格式化成易读的审核耗时。
 */
export function formatDuration(value?: number | string | null): string {
  const seconds = toNumber(value)
  if (seconds <= 0) {
    return '—'
  }
  if (seconds < 60) {
    return `${Math.round(seconds)} 秒`
  }
  if (seconds < 3600) {
    return `${Math.round(seconds / 60)} 分钟`
  }
  if (seconds < 86400) {
    return `${(seconds / 3600).toFixed(1)} 小时`
  }
  return `${(seconds / 86400).toFixed(1)} 天`
}
