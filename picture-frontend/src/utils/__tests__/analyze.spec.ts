import { describe, expect, it } from 'vitest'
import { formatCount, formatDuration, toNumber } from '@/utils/analyze'

describe('toNumber', () => {
  it('兼容字符串、空值和非法值', () => {
    expect(toNumber('42')).toBe(42)
    expect(toNumber(42)).toBe(42)
    expect(toNumber(undefined)).toBe(0)
    expect(toNumber(null)).toBe(0)
    expect(toNumber('')).toBe(0)
    expect(toNumber('abc')).toBe(0)
  })
})

describe('formatCount', () => {
  it('输出千分位', () => {
    expect(formatCount(12345)).toBe('12,345')
    expect(formatCount('1000000')).toBe('1,000,000')
    expect(formatCount(undefined)).toBe('0')
  })
})

describe('formatDuration', () => {
  it('按秒、分钟、小时、天展示', () => {
    expect(formatDuration(0)).toBe('—')
    expect(formatDuration(30)).toBe('30 秒')
    expect(formatDuration(90)).toBe('2 分钟')
    expect(formatDuration(5400)).toBe('1.5 小时')
    expect(formatDuration(172800)).toBe('2.0 天')
    expect(formatDuration('abc')).toBe('—')
  })
})
