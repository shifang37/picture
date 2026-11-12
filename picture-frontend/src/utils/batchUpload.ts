import { reactive } from 'vue'

/**
 * 批量上传的默认并发数：避免一次打满后端上传限流
 */
export const MAX_CONCURRENT_UPLOADS = 3

export interface BatchUploadResult {
  /** 新建/重传后的图片 id，用于上传完成后的批量设置信息 */
  pictureId?: string
  reviewStatus?: number
  message?: string
}

export type BatchUploadStatus = 'waiting' | 'uploading' | 'success' | 'error'

export interface BatchUploadItem {
  uid: string
  name: string
  file: File
  status: BatchUploadStatus
  message?: string
  pictureId?: string
  reviewStatus?: number
}

export interface BatchUploadSchedulerOptions {
  /**
   * 真正的上传逻辑，失败时抛错即可
   */
  upload: (file: File) => Promise<BatchUploadResult>
  /**
   * 最大并发数，默认 {@link MAX_CONCURRENT_UPLOADS}
   */
  concurrency?: number
}

export interface BatchUploadStats {
  waiting: number
  uploading: number
  success: number
  error: number
}

export interface BatchUploadScheduler {
  items: BatchUploadItem[]
  addFiles(files: File[]): void
  retry(uid: string): void
  clear(): void
  stats(): BatchUploadStats
  /**
   * 等待队列清空（供测试使用）
   */
  idle(): Promise<void>
}

/**
 * 创建批量上传调度器：限制并发、保留失败文件用于重试、维护展示列表。
 * 返回的 items 是 Vue 响应式数组，页面表格可直接绑定。
 */
export function createBatchUploadScheduler(
  options: BatchUploadSchedulerOptions,
): BatchUploadScheduler {
  const concurrency = Math.max(1, options.concurrency ?? MAX_CONCURRENT_UPLOADS)
  const items = reactive<BatchUploadItem[]>([])
  const pending: BatchUploadItem[] = []
  const idleResolvers: Array<() => void> = []
  let activeCount = 0
  let uidSeed = 0

  const resolveIdleIfNeeded = () => {
    if (activeCount === 0 && pending.length === 0) {
      idleResolvers.splice(0).forEach((resolve) => resolve())
    }
  }

  const pump = () => {
    while (activeCount < concurrency && pending.length > 0) {
      const next = pending.shift()
      if (next) {
        void run(next)
      }
    }
    resolveIdleIfNeeded()
  }

  const run = async (item: BatchUploadItem) => {
    activeCount++
    item.status = 'uploading'
    item.message = undefined
    try {
      const result = await options.upload(item.file)
      item.status = 'success'
      item.pictureId = result.pictureId
      item.reviewStatus = result.reviewStatus
      item.message = result.message
    } catch (e: any) {
      item.status = 'error'
      item.message = e?.message || '上传失败'
    } finally {
      activeCount--
      pump()
    }
  }

  const addFiles = (files: File[]) => {
    for (const file of files) {
      uidSeed++
      const item = reactive<BatchUploadItem>({
        uid: `upload-${Date.now()}-${uidSeed}`,
        name: file.name,
        file,
        status: 'waiting',
      })
      // 新加入的文件排在最前面，方便查看
      items.unshift(item)
      pending.push(item)
    }
    pump()
  }

  const retry = (uid: string) => {
    const item = items.find((current) => current.uid === uid)
    if (!item || item.status !== 'error') {
      return
    }
    item.status = 'waiting'
    item.message = undefined
    pending.push(item)
    pump()
  }

  const clear = () => {
    pending.length = 0
    // 只清空记录，不打断正在上传的任务
    for (let index = items.length - 1; index >= 0; index--) {
      const current = items[index]
      if (current && current.status !== 'uploading') {
        items.splice(index, 1)
      }
    }
    resolveIdleIfNeeded()
  }

  const stats = (): BatchUploadStats => {
    const result: BatchUploadStats = { waiting: 0, uploading: 0, success: 0, error: 0 }
    for (const item of items) {
      result[item.status]++
    }
    return result
  }

  const idle = () => {
    if (activeCount === 0 && pending.length === 0) {
      return Promise.resolve()
    }
    return new Promise<void>((resolve) => {
      idleResolvers.push(resolve)
    })
  }

  return { items, addFiles, retry, clear, stats, idle }
}
