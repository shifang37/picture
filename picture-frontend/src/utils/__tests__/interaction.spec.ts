import { describe, expect, it } from 'vitest'
import {
  applyOptimisticToggle,
  formatCount,
  mergeServerStatus,
  toCount,
  type InteractionState,
} from '../interaction'

const baseState: InteractionState = {
  favorite: false,
  like: false,
  favoriteCount: 3,
  likeCount: 0,
}

describe('toCount', () => {
  it('把后端返回的字符串计数转成数字', () => {
    expect(toCount('12')).toBe(12)
    expect(toCount(7)).toBe(7)
  })

  it('空值与非法值按 0 处理', () => {
    expect(toCount(undefined)).toBe(0)
    expect(toCount(null)).toBe(0)
    expect(toCount('')).toBe(0)
    expect(toCount('abc')).toBe(0)
    expect(toCount(-5)).toBe(0)
  })
})

describe('formatCount', () => {
  it('一万以内直接展示', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount('9999')).toBe('9999')
  })

  it('超过一万用「万」，并去掉多余的小数', () => {
    expect(formatCount(10000)).toBe('1万')
    expect(formatCount(12345)).toBe('1.2万')
    expect(formatCount(100000)).toBe('10万')
    expect(formatCount(2500000)).toBe('250万')
  })
})

describe('applyOptimisticToggle', () => {
  it('收藏：切换选中并同步加减计数', () => {
    const favorited = applyOptimisticToggle(baseState, 'favorite')
    expect(favorited.favorite).toBe(true)
    expect(favorited.favoriteCount).toBe(4)

    const canceled = applyOptimisticToggle(favorited, 'favorite')
    expect(canceled.favorite).toBe(false)
    expect(canceled.favoriteCount).toBe(3)
  })

  it('点赞：只影响点赞状态与计数', () => {
    const liked = applyOptimisticToggle(baseState, 'like')
    expect(liked.like).toBe(true)
    expect(liked.likeCount).toBe(1)
    expect(liked.favorite).toBe(false)
    expect(liked.favoriteCount).toBe(3)
  })

  it('计数不会减成负数', () => {
    const empty: InteractionState = { favorite: true, like: true, favoriteCount: 0, likeCount: 0 }
    const result = applyOptimisticToggle(empty, 'favorite')
    expect(result.favoriteCount).toBe(0)
    expect(result.likeCount).toBe(0)
  })
})

describe('mergeServerStatus', () => {
  it('用接口返回的真实状态覆盖本地状态', () => {
    const merged = mergeServerStatus(baseState, {
      favorite: true,
      like: true,
      favoriteCount: '10',
      likeCount: 2,
    })
    expect(merged).toEqual({ favorite: true, like: true, favoriteCount: 10, likeCount: 2 })
  })

  it('接口缺少字段时保留本地值，data 为空时原样返回', () => {
    expect(mergeServerStatus(baseState, { favorite: true })).toEqual({
      ...baseState,
      favorite: true,
    })
    expect(mergeServerStatus(baseState, null)).toBe(baseState)
  })
})
