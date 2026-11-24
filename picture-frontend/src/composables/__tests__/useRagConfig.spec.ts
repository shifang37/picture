import { beforeEach, describe, expect, it, vi } from 'vitest'

const { fetchRagConfig } = vi.hoisted(() => ({ fetchRagConfig: vi.fn() }))

vi.mock('@/api/ragExtra', () => ({
  fetchRagConfig,
}))

import { loadRagConfig, resetRagConfig, useRagConfig } from '@/composables/useRagConfig'

/**
 * RAG 开关是首页 / 顶栏 / 问答页共用的单例：
 * 只请求一次、失败时保持关闭、并发调用不重复打接口。
 */
describe('useRagConfig', () => {
  beforeEach(() => {
    resetRagConfig()
    fetchRagConfig.mockReset()
  })

  it('成功时按接口值开启对应能力', async () => {
    fetchRagConfig.mockResolvedValue({
      data: {
        code: 0,
        data: { enabled: true, hybridSearch: true, askEnabled: true, askDailyLimit: 5, askTopK: 6 },
      },
    })

    await loadRagConfig()

    const { ragConfig, ragConfigLoaded } = useRagConfig()
    expect(ragConfigLoaded.value).toBe(true)
    expect(ragConfig.value).toMatchObject({
      enabled: true,
      hybridSearch: true,
      askEnabled: true,
      askDailyLimit: 5,
      askTopK: 6,
    })
  })

  it('接口失败时保持关闭，不影响页面主流程', async () => {
    fetchRagConfig.mockRejectedValue(new Error('network down'))

    await expect(loadRagConfig()).resolves.toBeUndefined()

    const { ragConfig, ragConfigLoaded } = useRagConfig()
    expect(ragConfigLoaded.value).toBe(false)
    expect(ragConfig.value).toMatchObject({
      enabled: false,
      hybridSearch: false,
      askEnabled: false,
      askDailyLimit: 10,
      askTopK: 8,
    })
  })

  it('非法 / 缺失字段回退到安全默认值', async () => {
    fetchRagConfig.mockResolvedValue({
      data: { code: 0, data: { enabled: true, hybridSearch: true, askDailyLimit: 0 } },
    })

    await loadRagConfig()

    const { ragConfig } = useRagConfig()
    expect(ragConfig.value.askDailyLimit).toBe(10)
    expect(ragConfig.value.askEnabled).toBe(false)
    expect(ragConfig.value.askTopK).toBe(8)
  })

  it('并发调用共享同一个请求，已加载后不再重复请求', async () => {
    let resolveRequest: ((value: unknown) => void) | undefined
    fetchRagConfig.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveRequest = resolve
        }),
    )

    const first = loadRagConfig()
    const second = loadRagConfig()
    expect(fetchRagConfig).toHaveBeenCalledTimes(1)

    resolveRequest?.({
      data: { code: 0, data: { enabled: true, hybridSearch: false, askEnabled: false } },
    })
    await Promise.all([first, second])
    await loadRagConfig()

    expect(fetchRagConfig).toHaveBeenCalledTimes(1)
  })
})
