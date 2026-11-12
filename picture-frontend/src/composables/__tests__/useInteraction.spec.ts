import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// 组合式函数只负责编排：接口与全局提示都替换掉，断言「乐观更新 / 回滚 / 登录拦截」
const addInteraction = vi.fn()
const removeInteraction = vi.fn()
const getInteractionStatus = vi.fn()
const warning = vi.fn()
const error = vi.fn()

vi.mock('@/api/interactionExtra', () => ({
  addInteraction: (...args: unknown[]) => addInteraction(...args),
  removeInteraction: (...args: unknown[]) => removeInteraction(...args),
  getInteractionStatus: (...args: unknown[]) => getInteractionStatus(...args),
}))

vi.mock('ant-design-vue', () => ({
  message: {
    warning: (...args: unknown[]) => warning(...args),
    error: (...args: unknown[]) => error(...args),
  },
}))

import { useInteraction } from '../useInteraction'

const okStatus = (data: Record<string, unknown>) => ({ data: { code: 0, data } })

describe('useInteraction', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-12T10:00:00'))
    addInteraction.mockReset()
    removeInteraction.mockReset()
    getInteractionStatus.mockReset()
    warning.mockReset()
    error.mockReset()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('收藏：先乐观更新，再用服务端状态覆盖', async () => {
    addInteraction.mockResolvedValue(
      okStatus({ favorite: true, like: false, favoriteCount: '7', likeCount: '2' }),
    )
    const { state, toggle } = useInteraction({
      pictureId: '1',
      favoriteCount: '6',
      likeCount: '2',
      guard: () => true,
    })

    const pending = toggle('favorite')
    // 请求发出前本地已经 +1，按钮不会「点了没反应」
    expect(state.value.favorite).toBe(true)
    expect(state.value.favoriteCount).toBe(7)

    await pending
    expect(addInteraction).toHaveBeenCalledWith({ pictureId: '1', type: 'favorite' })
    expect(state.value.favoriteCount).toBe(7)
  })

  it('再次点击取消收藏：调用 remove 并回退计数', async () => {
    addInteraction.mockResolvedValue(
      okStatus({ favorite: true, like: false, favoriteCount: '1', likeCount: '0' }),
    )
    removeInteraction.mockResolvedValue(
      okStatus({ favorite: false, like: false, favoriteCount: '0', likeCount: '0' }),
    )
    const { state, toggle } = useInteraction({
      pictureId: '1',
      favoriteCount: '0',
      likeCount: '0',
      guard: () => true,
    })

    await toggle('favorite')
    // 越过 300ms 连点保护窗口
    vi.advanceTimersByTime(400)
    await toggle('favorite')

    expect(removeInteraction).toHaveBeenCalledTimes(1)
    expect(state.value.favorite).toBe(false)
    expect(state.value.favoriteCount).toBe(0)
  })

  it('300ms 内的连点被忽略，不产生第二个请求', async () => {
    addInteraction.mockResolvedValue(okStatus({ favorite: true, favoriteCount: '1' }))
    const { toggle } = useInteraction({ pictureId: '1', guard: () => true })

    await toggle('favorite')
    await toggle('favorite')

    expect(addInteraction).toHaveBeenCalledTimes(1)
  })

  it('接口返回业务错误时回滚快照并提示', async () => {
    addInteraction.mockResolvedValue({ data: { code: 40400, message: '图片不存在' } })
    const { state, toggle } = useInteraction({
      pictureId: '1',
      favoriteCount: '3',
      guard: () => true,
    })

    await toggle('favorite')

    expect(state.value.favorite).toBe(false)
    expect(state.value.favoriteCount).toBe(3)
    expect(error).toHaveBeenCalledWith('图片不存在')
  })

  it('网络异常时回滚，不把异常抛给调用方', async () => {
    addInteraction.mockRejectedValue(new Error('network'))
    const { state, toggle } = useInteraction({
      pictureId: '1',
      likeCount: '5',
      guard: () => true,
    })

    await expect(toggle('like')).resolves.toBeUndefined()
    expect(state.value.like).toBe(false)
    expect(state.value.likeCount).toBe(5)
    expect(error).toHaveBeenCalledWith('network')
  })

  it('未登录（guard 返回 false）时不发请求、不改状态', async () => {
    const { state, toggle } = useInteraction({
      pictureId: '1',
      favoriteCount: '2',
      guard: () => false,
    })

    await toggle('favorite')

    expect(addInteraction).not.toHaveBeenCalled()
    expect(state.value.favorite).toBe(false)
    expect(state.value.favoriteCount).toBe(2)
  })

  it('refresh 用服务端状态校正本地计数与选中态', async () => {
    getInteractionStatus.mockResolvedValue(
      okStatus({ favorite: true, like: true, favoriteCount: '9', likeCount: '4' }),
    )
    const { state, refresh } = useInteraction({ pictureId: '1' })

    await refresh()

    expect(getInteractionStatus).toHaveBeenCalledWith('1')
    expect(state.value).toMatchObject({
      favorite: true,
      like: true,
      favoriteCount: 9,
      likeCount: 4,
    })
  })

  it('refresh 失败时保留现有状态', async () => {
    getInteractionStatus.mockRejectedValue(new Error('network'))
    const { state, refresh } = useInteraction({ pictureId: '1', favoriteCount: '2' })

    await refresh()

    expect(state.value.favoriteCount).toBe(2)
    expect(warning).not.toHaveBeenCalled()
  })
})
