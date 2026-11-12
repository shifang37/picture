import { describe, expect, it } from 'vitest'
import {
  clearSearchHistory,
  pushSearchHistory,
  readSearchHistory,
  SEARCH_HISTORY_KEY,
  SEARCH_HISTORY_LIMIT,
  type StorageLike,
} from '../searchHistory'

/** 内存版 localStorage，行为与浏览器一致 */
class MemoryStorage implements StorageLike {
  private store = new Map<string, string>()

  getItem(key: string): string | null {
    return this.store.has(key) ? this.store.get(key)! : null
  }

  setItem(key: string, value: string): void {
    this.store.set(key, value)
  }

  removeItem(key: string): void {
    this.store.delete(key)
  }
}

/** 读 / 写都抛异常的实现，模拟隐私模式 */
class BrokenStorage implements StorageLike {
  getItem(): string | null {
    throw new Error('storage disabled')
  }

  setItem(): void {
    throw new Error('storage disabled')
  }

  removeItem(): void {
    throw new Error('storage disabled')
  }
}

describe('searchHistory', () => {
  it('写入后最近的排在最前，并返回新的历史', () => {
    const storage = new MemoryStorage()
    expect(pushSearchHistory('日落', storage)).toEqual(['日落'])
    expect(pushSearchHistory('星空', storage)).toEqual(['星空', '日落'])
    expect(readSearchHistory(storage)).toEqual(['星空', '日落'])
  })

  it('重复关键词去重并提到最前', () => {
    const storage = new MemoryStorage()
    pushSearchHistory('日落', storage)
    pushSearchHistory('星空', storage)
    expect(pushSearchHistory('日落', storage)).toEqual(['日落', '星空'])
  })

  it('去重按大小写不敏感，保留用户最新输入的写法', () => {
    const storage = new MemoryStorage()
    pushSearchHistory('sunset', storage)
    expect(pushSearchHistory('SUNSET', storage)).toEqual(['SUNSET'])
  })

  it('空白关键词被忽略', () => {
    const storage = new MemoryStorage()
    pushSearchHistory('日落', storage)
    expect(pushSearchHistory('   ', storage)).toEqual(['日落'])
  })

  it(`最多保留 ${SEARCH_HISTORY_LIMIT} 条，超出后丢弃最旧的`, () => {
    const storage = new MemoryStorage()
    for (let i = 0; i < SEARCH_HISTORY_LIMIT + 3; i += 1) {
      pushSearchHistory(`关键词${i}`, storage)
    }
    const history = readSearchHistory(storage)
    expect(history).toHaveLength(SEARCH_HISTORY_LIMIT)
    expect(history[0]).toBe(`关键词${SEARCH_HISTORY_LIMIT + 2}`)
    // 最早的几条已被挤出
    expect(history).not.toContain('关键词0')
  })

  it('清空后读到空数组', () => {
    const storage = new MemoryStorage()
    pushSearchHistory('日落', storage)
    clearSearchHistory(storage)
    expect(readSearchHistory(storage)).toEqual([])
    expect(storage.getItem(SEARCH_HISTORY_KEY)).toBeNull()
  })

  it('损坏的 JSON 或非数组内容按空历史处理', () => {
    const storage = new MemoryStorage()
    storage.setItem(SEARCH_HISTORY_KEY, '{not-json')
    expect(readSearchHistory(storage)).toEqual([])

    storage.setItem(SEARCH_HISTORY_KEY, '{"a":1}')
    expect(readSearchHistory(storage)).toEqual([])
  })

  it('忽略数组中的非字符串与空白项', () => {
    const storage = new MemoryStorage()
    storage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(['日落', 1, null, '  ', '星空']))
    expect(readSearchHistory(storage)).toEqual(['日落', '星空'])
  })

  it('storage 不可用时读写都不抛异常', () => {
    const storage = new BrokenStorage()
    expect(readSearchHistory(storage)).toEqual([])
    expect(pushSearchHistory('日落', storage)).toEqual([])
    expect(() => clearSearchHistory(storage)).not.toThrow()
  })
})
