import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getUnreadNotificationCount } from '@/api/notificationExtra'

/**
 * 轮询间隔：站内通知不引入 WebSocket，60 秒轮询足够，且页面不可见时暂停
 */
const POLL_INTERVAL = 60_000

/**
 * 顶栏未读红点状态。
 * <p>
 * 登录后启动轮询、登出后停止；页面切到后台时跳过请求，切回前台立即刷新一次，
 * 避免「挂在后台的标签页把接口打成 QPS 洪峰」。
 */
export const useNotificationStore = defineStore('notification', () => {
  const unreadCount = ref(0)

  let timer: ReturnType<typeof setInterval> | null = null
  let visibilityBound = false

  function hasDocument() {
    return typeof document !== 'undefined'
  }

  async function refreshUnread() {
    try {
      const res = await getUnreadNotificationCount()
      if (res.data.code === 0) {
        unreadCount.value = Number(res.data.data?.count ?? 0) || 0
      }
    } catch {
      // 轮询失败静默处理：红点停在旧值比弹一堆错误提示更友好
    }
  }

  function handleVisibilityChange() {
    if (hasDocument() && !document.hidden) {
      refreshUnread()
    }
  }

  function startPolling() {
    if (typeof window === 'undefined' || timer) {
      return
    }
    refreshUnread()
    timer = setInterval(() => {
      if (hasDocument() && document.hidden) {
        return
      }
      refreshUnread()
    }, POLL_INTERVAL)
    if (hasDocument() && !visibilityBound) {
      document.addEventListener('visibilitychange', handleVisibilityChange)
      visibilityBound = true
    }
  }

  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    if (hasDocument() && visibilityBound) {
      document.removeEventListener('visibilitychange', handleVisibilityChange)
      visibilityBound = false
    }
  }

  /**
   * 本地直接改未读数：标记已读后立即反馈，失败由调用方回滚
   */
  function setUnreadCount(count: number) {
    unreadCount.value = Math.max(0, Math.floor(Number(count) || 0))
  }

  function reset() {
    stopPolling()
    unreadCount.value = 0
  }

  return { unreadCount, refreshUnread, startPolling, stopPolling, setUnreadCount, reset }
})
