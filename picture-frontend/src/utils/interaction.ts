/**
 * 收藏 / 点赞的纯函数工具：计数格式化与乐观更新。
 * <p>
 * 抽成纯函数是为了让「乐观更新失败后回滚」这类容易出错的逻辑可以被 Vitest 直接覆盖，
 * 组件里只负责发请求与提示。
 */

export type InteractionKind = 'favorite' | 'like'

/** 前端使用的互动状态（计数统一用 number，避免字符串比较踩坑） */
export interface InteractionState {
  favorite: boolean
  like: boolean
  favoriteCount: number
  likeCount: number
}

/**
 * 后端把 Long 序列化成字符串，这里统一转成非负整数；非法值按 0 处理
 */
export function toCount(value?: number | string | null): number {
  if (value === undefined || value === null || value === '') {
    return 0
  }
  const count = Number(value)
  if (!Number.isFinite(count) || count < 0) {
    return 0
  }
  return Math.floor(count)
}

/**
 * 计数展示：超过 1 万用「万」，其余直接展示，避免热门图片把卡片撑变形
 */
export function formatCount(value?: number | string | null): string {
  const count = toCount(value)
  if (count < 10000) {
    return String(count)
  }
  const wan = count / 10000
  return `${wan >= 100 ? Math.round(wan) : wan.toFixed(1).replace(/\.0$/, '')}万`
}

/**
 * 乐观更新：先在本地点一次，再发请求；请求失败时由调用方用旧状态回滚
 */
export function applyOptimisticToggle(
  state: InteractionState,
  kind: InteractionKind,
): InteractionState {
  if (kind === 'favorite') {
    const favorite = !state.favorite
    return {
      ...state,
      favorite,
      favoriteCount: Math.max(0, state.favoriteCount + (favorite ? 1 : -1)),
    }
  }
  const like = !state.like
  return {
    ...state,
    like,
    likeCount: Math.max(0, state.likeCount + (like ? 1 : -1)),
  }
}

/**
 * 用接口返回的真实状态覆盖本地状态：接口是唯一事实来源，
 * 顺带修正并发点击导致的本地计数偏差。
 */
export function mergeServerStatus(
  state: InteractionState,
  data?: {
    favorite?: boolean
    like?: boolean
    favoriteCount?: number | string
    likeCount?: number | string
  } | null,
): InteractionState {
  if (!data) {
    return state
  }
  return {
    favorite: data.favorite ?? state.favorite,
    like: data.like ?? state.like,
    favoriteCount:
      data.favoriteCount === undefined ? state.favoriteCount : toCount(data.favoriteCount),
    likeCount: data.likeCount === undefined ? state.likeCount : toCount(data.likeCount),
  }
}
