import { describe, expect, it } from 'vitest'
import { createBatchUploadScheduler, MAX_CONCURRENT_UPLOADS } from '../batchUpload'

const createFile = (name: string) => new File(['content'], name, { type: 'image/jpeg' })

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

describe('batchUpload scheduler', () => {
  it('同时上传的文件数量不超过并发上限', async () => {
    let active = 0
    let maxActive = 0
    const scheduler = createBatchUploadScheduler({
      upload: async () => {
        active++
        maxActive = Math.max(maxActive, active)
        await sleep(5)
        active--
        return { reviewStatus: 1, message: 'ok' }
      },
    })

    scheduler.addFiles(Array.from({ length: 10 }, (_, index) => createFile(`${index}.jpg`)))
    await scheduler.idle()

    expect(maxActive).toBe(MAX_CONCURRENT_UPLOADS)
    expect(scheduler.stats()).toEqual({ waiting: 0, uploading: 0, success: 10, error: 0 })
    expect(scheduler.items).toHaveLength(10)
  })

  it('失败项保留 File，可以在重试后成功', async () => {
    let attempts = 0
    const scheduler = createBatchUploadScheduler({
      upload: async () => {
        attempts++
        if (attempts === 1) {
          throw new Error('文件大小不能超过 2M')
        }
        return { reviewStatus: 0, message: '待审核' }
      },
    })

    scheduler.addFiles([createFile('big.jpg')])
    await scheduler.idle()

    expect(scheduler.items[0].status).toBe('error')
    expect(scheduler.items[0].message).toBe('文件大小不能超过 2M')
    expect(scheduler.items[0].file.name).toBe('big.jpg')

    scheduler.retry(scheduler.items[0].uid)
    await scheduler.idle()

    expect(attempts).toBe(2)
    expect(scheduler.items[0].status).toBe('success')
    expect(scheduler.items[0].reviewStatus).toBe(0)
  })

  it('只有失败项可以重试，其他状态调用重试不会重复上传', async () => {
    let attempts = 0
    const scheduler = createBatchUploadScheduler({
      upload: async () => {
        attempts++
        return {}
      },
    })

    scheduler.addFiles([createFile('a.jpg')])
    await scheduler.idle()

    scheduler.retry(scheduler.items[0].uid)
    await scheduler.idle()

    expect(attempts).toBe(1)
  })

  it('清空记录会移除已完成项，不打断上传中的任务', async () => {
    let resolveUpload: (() => void) | undefined
    const scheduler = createBatchUploadScheduler({
      concurrency: 1,
      upload: () =>
        new Promise((resolve) => {
          resolveUpload = () => resolve({})
        }),
    })

    scheduler.addFiles([createFile('a.jpg'), createFile('b.jpg')])
    // 第一张处于上传中，第二张仍在排队
    expect(scheduler.stats().uploading).toBe(1)
    expect(scheduler.stats().waiting).toBe(1)

    scheduler.clear()

    expect(scheduler.items).toHaveLength(1)
    expect(scheduler.items[0].status).toBe('uploading')
    expect(scheduler.stats().waiting).toBe(0)

    resolveUpload?.()
    await scheduler.idle()
  })
})
