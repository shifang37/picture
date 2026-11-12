// 手动补充的搜索接口封装。
// 生成式客户端（mainController.ts 等）由 npm run openapi 重新生成，这里的内容不会被覆盖。
import request from '@/request'

/**
 * 搜索联想：名称 / 标签 / 分类的前缀匹配结果
 * @param q 搜索词，1–32 字符（首尾空白由后端裁剪）
 * @param limit 期望返回条数，后端收敛到 1–20
 */
export async function fetchSearchSuggest(q: string, limit = 10, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: API.SearchSuggestVO; message?: string }>(
    '/search/suggest',
    {
      method: 'GET',
      params: { q, limit },
      ...(options || {}),
    },
  )
}

/**
 * 热门搜索词：首页空结果与首次进入时展示
 * @param limit 期望返回条数，后端收敛到 1–20
 */
export async function fetchSearchHot(limit = 10, options?: { [key: string]: any }) {
  return request<{ code?: number; data?: API.SearchHotVO; message?: string }>('/search/hot', {
    method: 'GET',
    params: { limit },
    ...(options || {}),
  })
}
