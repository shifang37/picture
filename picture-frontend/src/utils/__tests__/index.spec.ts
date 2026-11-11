import { describe, expect, it } from 'vitest'
import { formatSize, getThumbnailUrl } from '@/utils'

describe('formatSize', () => {
  it('格式化 B / KB / MB', () => {
    expect(formatSize(0)).toBe('未知')
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(2048)).toBe('2.00 KB')
    expect(formatSize(2 * 1024 * 1024)).toBe('2.00 MB')
  })

  it('兼容后端 Long 字符串和空值', () => {
    expect(formatSize('1024')).toBe('1.00 KB')
    expect(formatSize(undefined)).toBe('未知')
    expect(formatSize('abc')).toBe('未知')
  })
})

describe('getThumbnailUrl', () => {
  it('为空时返回空字符串', () => {
    expect(getThumbnailUrl()).toBe('')
    expect(getThumbnailUrl('')).toBe('')
  })

  it('为 COS 地址追加缩略图参数', () => {
    expect(getThumbnailUrl('https://example.com/a.jpg')).toBe(
      'https://example.com/a.jpg?imageMogr2/thumbnail/300x',
    )
    expect(getThumbnailUrl('https://example.com/a.jpg?v=1')).toBe(
      'https://example.com/a.jpg?v=1&imageMogr2/thumbnail/300x',
    )
  })

  it('已有图片处理参数时不重复拼接', () => {
    const url = 'https://example.com/a.jpg?imageMogr2/thumbnail/200x'
    expect(getThumbnailUrl(url)).toBe(url)
  })
})
