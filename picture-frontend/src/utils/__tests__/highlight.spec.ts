import { describe, expect, it } from 'vitest'
import { containsKeyword, highlightSegments } from '../highlight'

describe('highlightSegments', () => {
  it('拼接所有片段后与原文完全一致', () => {
    const text = '海边的日落与日落后的星空'
    const segments = highlightSegments(text, '日落')
    expect(segments.map((segment) => segment.text).join('')).toBe(text)
  })

  it('命中全部出现位置，命中片段标记 matched', () => {
    const segments = highlightSegments('日落 日落', '日落')
    expect(segments).toEqual([
      { text: '日落', matched: true },
      { text: ' ', matched: false },
      { text: '日落', matched: true },
    ])
  })

  it('大小写不敏感，但保留原文大小写', () => {
    const segments = highlightSegments('Sunset SUNSET', 'sunset')
    expect(segments).toEqual([
      { text: 'Sunset', matched: true },
      { text: ' ', matched: false },
      { text: 'SUNSET', matched: true },
    ])
  })

  it('正则元字符按普通文本匹配', () => {
    const segments = highlightSegments('1+1=2 (括号)', '+1')
    expect(segments).toEqual([
      { text: '1', matched: false },
      { text: '+1', matched: true },
      { text: '=2 (括号)', matched: false },
    ])
  })

  it('XSS 输入只作为文本片段返回，不产生标记', () => {
    const text = '<script>alert(1)</script>'
    const segments = highlightSegments(text, '<script>')
    expect(segments).toEqual([
      { text: '<script>', matched: true },
      { text: 'alert(1)</script>', matched: false },
    ])
    expect(segments.map((segment) => segment.text).join('')).toBe(text)
  })

  it('空关键词返回整段未命中', () => {
    expect(highlightSegments('日落', '   ')).toEqual([{ text: '日落', matched: false }])
    expect(highlightSegments('日落', null)).toEqual([{ text: '日落', matched: false }])
  })

  it('空文本返回空数组', () => {
    expect(highlightSegments('', '日落')).toEqual([])
    expect(highlightSegments(undefined, '日落')).toEqual([])
  })

  it('关键词比文本长时不命中', () => {
    expect(highlightSegments('日', '日落')).toEqual([{ text: '日', matched: false }])
  })

  it('重叠关键词按非重叠方式切分', () => {
    expect(highlightSegments('aaa', 'aa')).toEqual([
      { text: 'aa', matched: true },
      { text: 'a', matched: false },
    ])
  })
})

describe('containsKeyword', () => {
  it('大小写不敏感判断是否命中', () => {
    expect(containsKeyword('Sunset', 'SUN')).toBe(true)
    expect(containsKeyword('Sunset', 'moon')).toBe(false)
  })

  it('空文本或空关键词返回 false', () => {
    expect(containsKeyword('', '日落')).toBe(false)
    expect(containsKeyword('日落', ' ')).toBe(false)
    expect(containsKeyword(null, '日落')).toBe(false)
  })
})
