/**
 * 分享 / SEO 元信息工具。
 * <p>
 * SPA 在服务端只有一个 `index.html`，社交平台的爬虫不会执行 JS，因此线上采用
 * 「nginx 按 UA 分流」：爬虫走后端 `GET /api/share/picture/{id}` 拿静态 OG 页面，
 * 人类访问时由这里的工具在浏览器里动态写 `document.title` 与 `og:*` 标签。
 * 两侧的标题 / 描述 / 封面规则保持一致，避免分享出去的内容与页面显示不一致。
 */

export const DEFAULT_SITE_NAME = '云图库'

/** 简介最大长度，与后端 ShareCardRenderer 保持一致 */
export const MAX_DESCRIPTION_LENGTH = 200

/** 标题最大长度：调用方已拼好「名称 - 云图库」时不再二次截断名称 */
export const MAX_TITLE_LENGTH = 100

export interface SeoMeta {
  /** 页面标题；为空时不覆盖 document.title，也不输出标题类标签 */
  title?: string
  /** 简介 / 描述 */
  description?: string
  /** 分享封面（一般用 getShareImageUrl 生成） */
  image?: string
  /** 页面规范地址（og:url） */
  url?: string
  /** 站点名，默认「云图库」 */
  siteName?: string
}

export interface SeoMetaTag {
  /** meta 上使用的属性名：name 或 property */
  attribute: 'name' | 'property'
  key: string
  content: string
}

/** 首次写入时记录的原始标题，reset 时恢复 */
let originalTitle: string | null = null

/**
 * 压掉换行与连续空白：meta 描述里的换行在社交平台上会显示成异常空白
 */
function collapseWhitespace(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

/**
 * 按 Unicode 码点截断，避免把 emoji 切成半个字符
 */
export function truncateText(
  text: string | undefined | null,
  maxLength = MAX_DESCRIPTION_LENGTH,
): string {
  const value = collapseWhitespace(text ?? '')
  if (!value) {
    return ''
  }
  const codePoints = Array.from(value)
  if (codePoints.length <= maxLength) {
    return value
  }
  return `${codePoints.slice(0, maxLength).join('')}…`
}

/**
 * 计算需要写入的 meta 标签（纯函数，便于单测）。
 *
 * @param meta 页面元信息
 * @returns 按固定顺序排列的 meta 列表；没有标题 / 描述 / 封面时不生成对应标签
 */
export function buildSeoMetaTags(meta: SeoMeta): SeoMetaTag[] {
  const siteName = (meta.siteName ?? '').trim() || DEFAULT_SITE_NAME
  const title = truncateText(meta.title, MAX_TITLE_LENGTH)
  const description = truncateText(meta.description)
  const image = (meta.image ?? '').trim()
  const url = (meta.url ?? '').trim()

  const tags: SeoMetaTag[] = []
  if (title) {
    tags.push({ attribute: 'property', key: 'og:type', content: 'article' })
    tags.push({ attribute: 'property', key: 'og:site_name', content: siteName })
    tags.push({ attribute: 'property', key: 'og:title', content: title })
  }
  if (description) {
    tags.push({ attribute: 'name', key: 'description', content: description })
    if (title) {
      tags.push({ attribute: 'property', key: 'og:description', content: description })
    }
  }
  if (url) {
    tags.push({ attribute: 'property', key: 'og:url', content: url })
  }
  if (image) {
    tags.push({ attribute: 'property', key: 'og:image', content: image })
    tags.push({ attribute: 'property', key: 'og:image:width', content: '1200' })
  }
  // 有封面才用大图卡片，否则降级成普通卡片，避免平台抓到空白图
  tags.push({
    attribute: 'name',
    key: 'twitter:card',
    content: image ? 'summary_large_image' : 'summary',
  })
  if (title) {
    tags.push({ attribute: 'name', key: 'twitter:title', content: title })
  }
  if (description) {
    tags.push({ attribute: 'name', key: 'twitter:description', content: description })
  }
  if (image) {
    tags.push({ attribute: 'name', key: 'twitter:image', content: image })
  }
  return tags
}

/**
 * 写入标题与 meta：已存在的同名标签只更新 content，缺失的创建并打上标记，
 * 这样 reset 时只清理自己创建的标签，不会误删 index.html 里的静态标签。
 */
export function applySeoMeta(meta: SeoMeta): void {
  if (typeof document === 'undefined') {
    return
  }
  if (originalTitle === null) {
    originalTitle = document.title
  }
  const title = truncateText(meta.title, MAX_TITLE_LENGTH)
  if (title) {
    document.title = title
  }
  for (const tag of buildSeoMetaTags(meta)) {
    const selector = `meta[${tag.attribute}="${tag.key}"]`
    let element = document.head.querySelector<HTMLMetaElement>(selector)
    if (!element) {
      element = document.createElement('meta')
      element.setAttribute(tag.attribute, tag.key)
      // 标记为脚本创建的标签，reset 时按此清理
      element.setAttribute('data-seo', 'managed')
      document.head.appendChild(element)
    }
    element.setAttribute('content', tag.content)
  }
}

/**
 * 清理动态写入的 meta 并恢复原始标题（页面离开时调用）
 */
export function resetSeoMeta(): void {
  if (typeof document === 'undefined') {
    return
  }
  document.head.querySelectorAll('meta[data-seo="managed"]').forEach((element) => {
    element.remove()
  })
  if (originalTitle !== null) {
    document.title = originalTitle
    originalTitle = null
  }
}
