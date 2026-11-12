/**
 * 体积格式化：上传提示（3.2MB → 1.8MB）与配额展示共用同一套规则。
 */

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/**
 * 把字节数格式化成便于阅读的文本，默认保留 1 位小数
 *
 * @param bytes 字节数；非法值按 0 处理
 * @param fractionDigits 小数位（0–2）
 */
export function formatBytes(bytes: number | null | undefined, fractionDigits = 1): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes <= 0) {
    return '0B'
  }
  const digits = Math.min(2, Math.max(0, Math.trunc(fractionDigits)))
  let value = bytes
  let unitIndex = 0
  while (value >= 1024 && unitIndex < UNITS.length - 1) {
    value /= 1024
    unitIndex++
  }
  // 1023.95KB 这类值四舍五入后会变成 1024KB，需要再进一位
  if (Number(value.toFixed(digits)) >= 1024 && unitIndex < UNITS.length - 1) {
    value /= 1024
    unitIndex++
  }
  const text = digits === 0 ? String(Math.round(value)) : value.toFixed(digits)
  // 去掉 "1.0MB" 这种无意义的小数尾巴
  return `${text.replace(/\.0+$/, '')}${UNITS[unitIndex]}`
}

/**
 * 计算百分比（0–100 的整数），分母为 0 时返回 0
 */
export function percentOf(
  used: number | null | undefined,
  total: number | null | undefined,
): number {
  if (used == null || total == null || total <= 0 || used <= 0) {
    return 0
  }
  return Math.min(100, Math.round((used / total) * 100))
}
