import { describe, expect, it } from 'vitest'
import {
  WATERFALL_GAP,
  distributeToColumns,
  getColumnCount,
  getColumnWidth,
  getPictureAspectRatio,
  getThumbnailSize,
} from '@/utils/waterfall'

describe('getColumnCount', () => {
  it('按容器宽度计算列数并限制在 1–6 列', () => {
    expect(getColumnCount(320)).toBe(1)
    expect(getColumnCount(700)).toBe(2)
    expect(getColumnCount(1000)).toBe(3)
    expect(getColumnCount(1200)).toBe(4)
    expect(getColumnCount(1600)).toBe(6)
    expect(getColumnCount(4000)).toBe(6)
  })

  it('宽度非法时按默认宽度估算，而不是退化为单列', () => {
    expect(getColumnCount(0)).toBe(4)
    expect(getColumnCount(Number.NaN)).toBe(4)
    expect(getColumnCount(-100)).toBe(4)
  })
})

describe('getColumnWidth', () => {
  it('扣除列间距后平均分配', () => {
    expect(getColumnWidth(1000, 3)).toBeCloseTo((1000 - WATERFALL_GAP * 2) / 3)
    expect(getColumnWidth(0, 3)).toBe(0)
  })
})

describe('getPictureAspectRatio', () => {
  it('返回高宽比', () => {
    expect(getPictureAspectRatio({ picWidth: 1200, picHeight: 800 })).toBeCloseTo(2 / 3)
    expect(getPictureAspectRatio({ picWidth: 800, picHeight: 1200 })).toBeCloseTo(1.5)
  })

  it('宽高缺失或非法时回退为 1', () => {
    expect(getPictureAspectRatio({})).toBe(1)
    expect(getPictureAspectRatio({ picWidth: 0, picHeight: 100 })).toBe(1)
    expect(getPictureAspectRatio({ picWidth: 100, picHeight: -1 })).toBe(1)
  })
})

describe('distributeToColumns', () => {
  const items = [
    { id: 'a', ratio: 1 },
    { id: 'b', ratio: 0.5 },
    { id: 'c', ratio: 2 },
    { id: 'd', ratio: 0.5 },
    { id: 'e', ratio: 0.5 },
  ]

  it('把每张图片放入当前最矮的一列', () => {
    const columns = distributeToColumns(items, 2, (item) => item.ratio)
    expect(columns.map((column) => column.map((item) => item.id))).toEqual([
      ['a', 'd', 'e'],
      ['b', 'c'],
    ])
  })

  it('列数至少为 1，且保留列内顺序', () => {
    const columns = distributeToColumns(items, 0, (item) => item.ratio)
    expect(columns).toHaveLength(1)
    expect(columns[0]?.map((item) => item.id)).toEqual(['a', 'b', 'c', 'd', 'e'])
  })

  it('比例非法时按正方形参与计算', () => {
    const columns = distributeToColumns(
      [
        { id: 'a', ratio: Number.NaN },
        { id: 'b', ratio: 1 },
      ],
      2,
      (item) => item.ratio,
    )
    expect(columns.map((column) => column.length)).toEqual([1, 1])
  })
})

describe('getThumbnailSize', () => {
  it('按列宽和像素比取整到百位并限制在 300–600', () => {
    expect(getThumbnailSize(150, 1)).toBe(300)
    expect(getThumbnailSize(200, 2)).toBe(400)
    expect(getThumbnailSize(300, 1)).toBe(300)
    expect(getThumbnailSize(2000, 3)).toBe(600)
  })

  it('非法参数时回退到默认值', () => {
    expect(getThumbnailSize(0, 1)).toBe(300)
    expect(getThumbnailSize(200, 0)).toBe(300)
  })
})
