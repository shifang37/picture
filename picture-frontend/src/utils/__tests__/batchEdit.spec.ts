import { describe, expect, it } from 'vitest'
import { buildBatchEditPayload } from '@/utils/batchEdit'

describe('buildBatchEditPayload', () => {
  it('没动过的字段不提交，避免误覆盖', () => {
    const payload = buildBatchEditPayload({
      category: undefined,
      tags: undefined,
      tagsTouched: false,
      introduction: '',
    })

    expect(payload).toEqual({
      category: undefined,
      tags: undefined,
      introduction: undefined,
      hasChanges: false,
    })
  })

  it('只提交填写的分类，并去掉首尾空白', () => {
    const payload = buildBatchEditPayload({
      category: '  风景  ',
      tagsTouched: false,
      introduction: '   ',
    })

    expect(payload.category).toBe('风景')
    expect(payload.introduction).toBeUndefined()
    expect(payload.hasChanges).toBe(true)
  })

  it('标签动过但为空时提交空数组，表示清空标签', () => {
    const payload = buildBatchEditPayload({
      tags: [],
      tagsTouched: true,
      introduction: '',
    })

    expect(payload.tags).toEqual([])
    expect(payload.hasChanges).toBe(true)
  })

  it('标签没动过时不提交，即使表单里有历史值', () => {
    const payload = buildBatchEditPayload({
      tags: ['历史标签'],
      tagsTouched: false,
      introduction: '',
    })

    expect(payload.tags).toBeUndefined()
    expect(payload.hasChanges).toBe(false)
  })

  it('复制标签数组，避免后续表单修改影响已提交的参数', () => {
    const tags = ['风景', '夜景']
    const payload = buildBatchEditPayload({ tags, tagsTouched: true, introduction: '' })

    expect(payload.tags).toEqual(['风景', '夜景'])
    expect(payload.tags).not.toBe(tags)
  })
})
