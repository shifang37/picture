import { describe, expect, it } from 'vitest'
import { formatBytes, percentOf } from '../size'

describe('formatBytes', () => {
  it('按 B / KB / MB / GB 逐级换算并去掉无意义的小数', () => {
    expect(formatBytes(0)).toBe('0B')
    expect(formatBytes(512)).toBe('512B')
    expect(formatBytes(1024)).toBe('1KB')
    expect(formatBytes(1536)).toBe('1.5KB')
    expect(formatBytes(2 * 1024 * 1024)).toBe('2MB')
    expect(formatBytes(3.2 * 1024 * 1024)).toBe('3.2MB')
    expect(formatBytes(1024 * 1024 * 1024)).toBe('1GB')
  })

  it('非法输入按 0 处理', () => {
    expect(formatBytes(null)).toBe('0B')
    expect(formatBytes(undefined)).toBe('0B')
    expect(formatBytes(-1)).toBe('0B')
    expect(formatBytes(Number.NaN)).toBe('0B')
  })

  it('保留小数位可配置，且能带出 0 位小数', () => {
    expect(formatBytes(1536, 0)).toBe('2KB')
    expect(formatBytes(1.234 * 1024 * 1024, 2)).toBe('1.23MB')
  })

  it('四舍五入到 1024 时自动进位到下一个单位', () => {
    expect(formatBytes(1023.96 * 1024)).toBe('1MB')
  })
})

describe('percentOf', () => {
  it('计算 0–100 的整数百分比', () => {
    expect(percentOf(0, 100)).toBe(0)
    expect(percentOf(1, 3)).toBe(33)
    expect(percentOf(50, 100)).toBe(50)
    expect(percentOf(100, 100)).toBe(100)
    expect(percentOf(150, 100)).toBe(100)
  })

  it('分母为 0 或非法时返回 0', () => {
    expect(percentOf(10, 0)).toBe(0)
    expect(percentOf(10, null)).toBe(0)
    expect(percentOf(null, 100)).toBe(0)
    expect(percentOf(-5, 100)).toBe(0)
  })
})
