import { getCurrentInstance, reactive, ref, toValue, type MaybeRefOrGetter } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { addInteraction, getInteractionStatus, removeInteraction } from '@/api/interactionExtra'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import {
  applyOptimisticToggle,
  mergeServerStatus,
  toCount,
  type InteractionKind,
  type InteractionState,
} from '@/utils/interaction'

/**
 * 收藏 / 点赞的组件逻辑：详情页按钮与首页卡片共用。
 * <p>
 * 把「登录拦截 → 乐观更新 → 请求 → 用服务端状态覆盖 / 失败回滚」收敛到一处，
 * 避免卡片和详情页各写一套后行为不一致（例如只有详情页做了回滚）。
 * 纯计算部分放在 {@link applyOptimisticToggle} 等工具函数里，方便 Vitest 覆盖。
 */
export interface UseInteractionOptions {
  /** 图片 id；用 getter 传入，列表复用组件时不会拿到上一次的 id */
  pictureId: MaybeRefOrGetter<string>
  /** 服务端返回的初始计数（列表缓存里的值可能略滞后，详情/状态接口会校正） */
  favoriteCount?: MaybeRefOrGetter<number | string | null | undefined>
  likeCount?: MaybeRefOrGetter<number | string | null | undefined>
  /** 登录校验；返回 false 表示已引导登录，本次点击不再发请求（测试可注入） */
  guard?: () => boolean
  /** 连点保护窗口，默认 300ms */
  debounceMs?: number
}

export function useInteraction(options: UseInteractionOptions) {
  // 只有处于组件 setup 中才解析路由：单元测试直接调用组合式函数时没有路由上下文
  const hasSetupContext = getCurrentInstance() !== null
  const router = hasSetupContext ? useRouter() : undefined
  const route = hasSetupContext ? useRoute() : undefined

  const state = ref<InteractionState>({
    favorite: false,
    like: false,
    favoriteCount: toCount(toValue(options.favoriteCount)),
    likeCount: toCount(toValue(options.likeCount)),
  })

  const loading = reactive({ favorite: false, like: false })

  // 连点保护：同一按钮窗口期内的第二次点击直接忽略，避免产生大量请求
  const lastClickAt = reactive<Record<InteractionKind, number>>({ favorite: 0, like: 0 })
  const debounceMs = options.debounceMs ?? 300

  const requireLogin = () => {
    const loginUserStore = useLoginUserStore()
    if (loginUserStore.loginUser.id) {
      return true
    }
    message.warning('请先登录')
    if (router && route) {
      router.push(`/user/login?redirect=${encodeURIComponent(route.fullPath)}`)
    }
    return false
  }

  /** 拉取服务端当前状态与计数；失败时保持现有状态，不打断浏览 */
  const refresh = async () => {
    try {
      const res = await getInteractionStatus(toValue(options.pictureId))
      if (res.data.code === 0) {
        state.value = mergeServerStatus(state.value, res.data.data)
      }
    } catch {
      // 状态查询失败时保持「未选中 + 服务端计数」，用户点击仍可正常发起互动
    }
  }

  const toggle = async (kind: InteractionKind) => {
    if (!(options.guard ?? requireLogin)()) {
      return
    }
    const now = Date.now()
    if (now - lastClickAt[kind] < debounceMs) {
      return
    }
    lastClickAt[kind] = now

    // 乐观更新：先改本地，失败时用快照回滚
    const snapshot = { ...state.value }
    state.value = applyOptimisticToggle(state.value, kind)
    const shouldAdd = kind === 'favorite' ? state.value.favorite : state.value.like
    loading[kind] = true
    try {
      const res = shouldAdd
        ? await addInteraction({ pictureId: toValue(options.pictureId), type: kind })
        : await removeInteraction({ pictureId: toValue(options.pictureId), type: kind })
      if (res.data.code === 0) {
        state.value = mergeServerStatus(state.value, res.data.data)
      } else {
        state.value = snapshot
        message.error(res.data.message || '操作失败，请稍后重试')
      }
    } catch (error: any) {
      state.value = snapshot
      message.error(error?.message || '网络异常，请稍后重试')
    } finally {
      loading[kind] = false
    }
  }

  return { state, loading, toggle, refresh }
}
