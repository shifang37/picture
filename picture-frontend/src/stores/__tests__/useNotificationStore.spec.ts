import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 轮询逻辑只依赖未读接口，这里直接把它替换掉，避免真实请求
const getUnreadNotificationCount = vi.fn()

vi.mock('@/api/notificationExtra', () => ({
  getUnreadNotificationCount: () => getUnreadNotificationCount(),
}))

import { useNotificationStore } from '../useNotificationStore'

describe('useNotificationStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    getUnreadNotificationCount.mockReset()
    getUnreadNotificationCount.mockResolvedValue({ data: { code: 0, data: { count: 3 } } })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('刷新未读数：读取接口返回值', async () => {
    const store = useNotificationStore()

    await store.refreshUnread()

    expect(store.unreadCount).toBe(3)
  })

  it('接口异常时保留旧值，不抛出', async () => {
    const store = useNotificationStore()
    store.setUnreadCount(5)
    getUnreadNotificationCount.mockRejectedValue(new Error('network'))

    await expect(store.refreshUnread()).resolves.toBeUndefined()
    expect(store.unreadCount).toBe(5)
  })

  it('startPolling 只注册一个定时器，重复调用不叠加', async () => {
    const store = useNotificationStore()

    store.startPolling()
    store.startPolling()
    await vi.advanceTimersByTimeAsync(60_000)

    // 首次立即刷新 + 一个轮询周期，共 2 次
    expect(getUnreadNotificationCount).toHaveBeenCalledTimes(2)

    store.stopPolling()
  })

  it('停止轮询后不再请求，登出时清零未读数', async () => {
    const store = useNotificationStore()
    store.startPolling()
    store.stopPolling()
    const callsAfterStop = getUnreadNotificationCount.mock.calls.length

    await vi.advanceTimersByTimeAsync(120_000)
    expect(getUnreadNotificationCount).toHaveBeenCalledTimes(callsAfterStop)

    store.setUnreadCount(9)
    store.reset()
    expect(store.unreadCount).toBe(0)
  })

  it('未读数不会变成负数', () => {
    const store = useNotificationStore()

    store.setUnreadCount(-3)
    expect(store.unreadCount).toBe(0)
  })
})
