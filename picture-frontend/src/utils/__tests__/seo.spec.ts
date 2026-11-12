import { afterEach, describe, expect, it } from 'vitest'
import {
  applySeoMeta,
  buildSeoMetaTags,
  DEFAULT_SITE_NAME,
  MAX_DESCRIPTION_LENGTH,
  resetSeoMeta,
  truncateText,
} from '../seo'

const metaContent = (attribute: 'name' | 'property', key: string) =>
  document.head.querySelector<HTMLMetaElement>(`meta[${attribute}="${key}"]`)?.content

describe('truncateText', () => {
  it('压掉换行与连续空白', () => {
    expect(truncateText('第一行\n第二行\r\n第三行')).toBe('第一行 第二行 第三行')
  })

  it('空值返回空串', () => {
    expect(truncateText(undefined)).toBe('')
    expect(truncateText('   ')).toBe('')
  })

  it('超长文本按码点截断并补省略号', () => {
    const text = '图'.repeat(MAX_DESCRIPTION_LENGTH + 5)
    const truncated = truncateText(text)
    expect(truncated).toHaveLength(MAX_DESCRIPTION_LENGTH + 1)
    expect(truncated.endsWith('…')).toBe(true)
  })

  it('不会把 emoji 切成半个字符', () => {
    expect(truncateText('😀😀😀', 2)).toBe('😀😀…')
  })
})

describe('buildSeoMetaTags', () => {
  it('完整信息生成 OG 与 Twitter 标签', () => {
    const tags = buildSeoMetaTags({
      title: '日落海边 - 云图库',
      description: '夏天傍晚的海边',
      image: 'https://cos.example.com/1.jpg?imageMogr2/thumbnail/1200x',
      url: 'https://img.example.com/picture/1',
    })

    expect(tags).toContainEqual({
      attribute: 'property',
      key: 'og:title',
      content: '日落海边 - 云图库',
    })
    expect(tags).toContainEqual({
      attribute: 'property',
      key: 'og:description',
      content: '夏天傍晚的海边',
    })
    expect(tags).toContainEqual({
      attribute: 'property',
      key: 'og:image',
      content: 'https://cos.example.com/1.jpg?imageMogr2/thumbnail/1200x',
    })
    expect(tags).toContainEqual({
      attribute: 'property',
      key: 'og:url',
      content: 'https://img.example.com/picture/1',
    })
    expect(tags).toContainEqual({
      attribute: 'property',
      key: 'og:site_name',
      content: DEFAULT_SITE_NAME,
    })
    expect(tags).toContainEqual({
      attribute: 'name',
      key: 'twitter:card',
      content: 'summary_large_image',
    })
    expect(tags.some((tag) => tag.key === 'description' && tag.attribute === 'name')).toBe(true)
  })

  it('没有封面时降级成普通卡片', () => {
    const tags = buildSeoMetaTags({ title: '无封面', description: '描述' })
    expect(tags).toContainEqual({ attribute: 'name', key: 'twitter:card', content: 'summary' })
    expect(tags.some((tag) => tag.key === 'og:image')).toBe(false)
  })

  it('没有标题时不输出标题类标签', () => {
    const tags = buildSeoMetaTags({ description: '只有描述' })
    expect(tags.some((tag) => tag.key === 'og:title')).toBe(false)
    expect(tags.some((tag) => tag.key === 'twitter:title')).toBe(false)
    expect(tags.some((tag) => tag.key === 'og:type')).toBe(false)
  })

  it('站点名可覆盖，空白时回落到默认值', () => {
    expect(buildSeoMetaTags({ title: 't', siteName: '我的图库' })).toContainEqual({
      attribute: 'property',
      key: 'og:site_name',
      content: '我的图库',
    })
    expect(buildSeoMetaTags({ title: 't', siteName: '   ' })).toContainEqual({
      attribute: 'property',
      key: 'og:site_name',
      content: DEFAULT_SITE_NAME,
    })
  })
})

describe('applySeoMeta / resetSeoMeta', () => {
  afterEach(() => {
    resetSeoMeta()
  })

  it('写入标题与 meta，重复调用不会产生重复标签', () => {
    applySeoMeta({
      title: '日落海边 - 云图库',
      description: '夏天傍晚的海边',
      image: 'https://cos.example.com/1.jpg',
      url: 'https://img.example.com/picture/1',
    })
    applySeoMeta({
      title: '日落海边 - 云图库',
      description: '夏天傍晚的海边',
      image: 'https://cos.example.com/1.jpg',
      url: 'https://img.example.com/picture/1',
    })

    expect(document.title).toBe('日落海边 - 云图库')
    expect(metaContent('property', 'og:title')).toBe('日落海边 - 云图库')
    expect(metaContent('name', 'twitter:card')).toBe('summary_large_image')
    expect(document.head.querySelectorAll('meta[property="og:title"]')).toHaveLength(1)
  })

  it('reset 清理动态标签并恢复原始标题', () => {
    const original = document.title
    applySeoMeta({ title: '临时标题', description: '描述' })
    expect(document.head.querySelectorAll('meta[data-seo="managed"]').length).toBeGreaterThan(0)

    resetSeoMeta()

    expect(document.title).toBe(original)
    expect(document.head.querySelectorAll('meta[data-seo="managed"]')).toHaveLength(0)
    expect(metaContent('property', 'og:title')).toBeUndefined()
  })

  it('不覆盖已存在的静态 meta，reset 时也不会删除它', () => {
    const existing = document.createElement('meta')
    existing.setAttribute('name', 'twitter:card')
    existing.setAttribute('content', 'summary')
    document.head.appendChild(existing)

    applySeoMeta({ title: '有封面', image: 'https://cos.example.com/1.jpg' })
    expect(existing.getAttribute('content')).toBe('summary_large_image')

    resetSeoMeta()
    // 静态标签保留（只是内容被更新过，属于页面自身配置）
    expect(document.head.contains(existing)).toBe(true)
    existing.remove()
  })
})
