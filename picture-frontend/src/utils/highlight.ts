/**
 * 搜索结果高亮：把文本按关键词切成「命中 / 未命中」片段。
 * <p>
 * 故意不返回 HTML 字符串，也不使用 `v-html`：页面用文本插值逐段渲染，Vue 自己会做转义，
 * 因此 `<script>`、`&`、引号之类的输入天然安全，不存在「先替换再渲染」的 XSS 面。
 * 关键词用 `indexOf` 而不是正则匹配，`(`、`.`、`*` 等正则元字符不需要额外转义。
 */

export interface HighlightSegment {
  /** 片段文本（原样，不含任何标记） */
  text: string
  /** 是否为关键词命中片段 */
  matched: boolean
}

/**
 * 按关键词切分文本（大小写不敏感，命中全部出现位置）。
 *
 * @param text 原始文本，null / undefined 视为空文本
 * @param keyword 关键词，首尾空白会被裁剪；为空时整段作为未命中片段返回
 * @returns 片段数组；文本为空时返回空数组
 */
export function highlightSegments(
  text?: string | null,
  keyword?: string | null,
): HighlightSegment[] {
  const source = text ?? ''
  if (!source) {
    return []
  }
  const needle = (keyword ?? '').trim()
  if (!needle) {
    return [{ text: source, matched: false }]
  }
  // 某些字符（如 'İ'）小写后长度会变化，会让下标错位；长度不一致时退回大小写敏感匹配
  const lowerSource = source.toLowerCase()
  const lowerNeedle = needle.toLowerCase()
  const caseInsensitive =
    lowerSource.length === source.length && lowerNeedle.length === needle.length
  const haystack = caseInsensitive ? lowerSource : source
  const target = caseInsensitive ? lowerNeedle : needle

  const segments: HighlightSegment[] = []
  let cursor = 0
  while (cursor < source.length) {
    const index = haystack.indexOf(target, cursor)
    if (index < 0) {
      segments.push({ text: source.slice(cursor), matched: false })
      break
    }
    if (index > cursor) {
      segments.push({ text: source.slice(cursor, index), matched: false })
    }
    segments.push({ text: source.slice(index, index + target.length), matched: true })
    cursor = index + target.length
  }
  return segments
}

/**
 * 文本是否命中关键词（大小写不敏感），用于决定是否展示高亮样式
 */
export function containsKeyword(text?: string | null, keyword?: string | null): boolean {
  const needle = (keyword ?? '').trim().toLowerCase()
  if (!text || !needle) {
    return false
  }
  return text.toLowerCase().includes(needle)
}
