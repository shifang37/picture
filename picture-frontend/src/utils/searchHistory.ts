/**
 * 搜索历史：只存在浏览器 localStorage 里，不上报后端。
 * <p>
 * 抽成纯函数并允许注入 storage，方便在 jsdom（无持久化 / 隐私模式抛异常）下测试，
 * 也避免组件里散落 try/catch。
 */

export const SEARCH_HISTORY_KEY = 'picture:search-history'

/** 历史记录最多保留 10 条，超出后丢弃最旧的 */
export const SEARCH_HISTORY_LIMIT = 10

/** 只用到这三个方法，注入假对象即可测试 */
export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function resolveStorage(storage?: StorageLike | null): StorageLike | null {
  if (storage !== undefined) {
    return storage
  }
  try {
    if (typeof window === 'undefined' || !window.localStorage) {
      return null
    }
    return window.localStorage
  } catch {
    // 隐私模式下访问 localStorage 可能直接抛异常
    return null
  }
}

function normalizeKeyword(keyword?: string | null): string {
  return (keyword ?? '').trim()
}

/**
 * 读取搜索历史（最近的在前）；损坏的 JSON、非字符串项都会被忽略
 */
export function readSearchHistory(storage?: StorageLike | null): string[] {
  const store = resolveStorage(storage)
  if (!store) {
    return []
  }
  let raw: string | null = null
  try {
    raw = store.getItem(SEARCH_HISTORY_KEY)
  } catch {
    return []
  }
  if (!raw) {
    return []
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) {
    return []
  }
  const result: string[] = []
  const seen = new Set<string>()
  for (const item of parsed) {
    if (typeof item !== 'string') {
      continue
    }
    const keyword = normalizeKeyword(item)
    const key = keyword.toLowerCase()
    if (!keyword || seen.has(key)) {
      continue
    }
    seen.add(key)
    result.push(keyword)
    if (result.length >= SEARCH_HISTORY_LIMIT) {
      break
    }
  }
  return result
}

/**
 * 写入一条搜索历史：空白关键词直接忽略，重复关键词提到最前面，最多保留 10 条
 *
 * @returns 写入后的完整历史（storage 不可用时返回当前读到的最新值）
 */
export function pushSearchHistory(keyword: string, storage?: StorageLike | null): string[] {
  const value = normalizeKeyword(keyword)
  const current = readSearchHistory(storage)
  if (!value) {
    return current
  }
  const key = value.toLowerCase()
  const next = [value, ...current.filter((item) => item.toLowerCase() !== key)].slice(
    0,
    SEARCH_HISTORY_LIMIT,
  )
  const store = resolveStorage(storage)
  if (!store) {
    return current
  }
  try {
    store.setItem(SEARCH_HISTORY_KEY, JSON.stringify(next))
    return next
  } catch {
    // 配额写满或隐私模式：历史只是便利功能，写入失败不影响搜索，也不展示未持久化的条目
    return current
  }
}

/**
 * 清空搜索历史
 */
export function clearSearchHistory(storage?: StorageLike | null): void {
  const store = resolveStorage(storage)
  if (!store) {
    return
  }
  try {
    store.removeItem(SEARCH_HISTORY_KEY)
  } catch {
    // 忽略：清空失败不影响页面
  }
}
