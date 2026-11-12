import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'
// 1x1 透明 PNG，避免测试依赖外网图片
const MOCK_IMAGE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

const makePicture = (id: string, name: string, overrides: Record<string, unknown> = {}) => ({
  id,
  url: `https://example.invalid/mock-images/${id}.jpg`,
  name,
  introduction: 'M3 E2E 图片',
  category: '风景',
  tags: ['测试'],
  picWidth: 1200,
  picHeight: 800,
  reviewStatus: 1,
  favoriteCount: '1',
  likeCount: '1',
  downloadCount: '1',
  ...overrides,
})

/**
 * M3 搜索相关的公共 Mock：未登录、字典、热词、联想，列表结果由 listHandler 决定。
 */
const mockSearchApi = async (
  page: Page,
  options: {
    list: (body: Record<string, any>) => { records: Record<string, unknown>[]; total: number }
    suggest?: (q: string) => Record<string, unknown>
    hot?: Record<string, unknown>
  },
) => {
  await page.route('**/mock-images/**', (route) =>
    route.fulfill({ body: MOCK_IMAGE, contentType: 'image/png' }),
  )
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: { code: 40100, data: null, message: '未登录' } })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      const body = route.request().postDataJSON() as Record<string, any>
      await route.fulfill({ json: { code: 0, data: options.list(body), message: 'ok' } })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({
        json: { code: 0, data: { tagList: ['测试'], categoryList: ['风景'] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/search/hot')) {
      await route.fulfill({
        json: {
          code: 0,
          data: options.hot ?? {
            tags: [{ name: '星空', count: 3 }],
            categories: [{ name: '人物', count: 2 }],
          },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/search/suggest')) {
      const q = new URL(url).searchParams.get('q') ?? ''
      await route.fulfill({
        json: {
          code: 0,
          data: options.suggest?.(q) ?? { names: [], tags: [], categories: [] },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })
}

const searchInput = (page: Page) => page.getByTestId('search-input').locator('input')

test('首页搜索：联想建议点击后按关键词搜索，命中名称高亮', async ({ page }) => {
  const listBodies: Record<string, any>[] = []
  let suggestQuery = ''

  await mockSearchApi(page, {
    list: (body) => {
      listBodies.push(body)
      return {
        records: [makePicture('1', '日落海边与晚霞'), makePicture('2', '城市夜景')],
        total: 2,
      }
    },
    suggest: (q) => {
      suggestQuery = q
      return {
        names: [{ id: '1', name: '日落海边与晚霞' }],
        tags: ['日落'],
        categories: ['日落风景'],
      }
    },
  })

  await page.goto('/')
  await expect(page.getByTestId('waterfall')).toBeVisible()

  await searchInput(page).fill('日落')
  // 联想是防抖请求，等待下拉框出现分组标题与建议
  await expect(page.locator('.ant-select-item-option').filter({ hasText: '日落海边与晚霞' })).toBeVisible()
  expect(suggestQuery).toBe('日落')
  // 名称 / 标签 / 分类三个分组都来自接口
  await expect(page.locator('.ant-select-item-group').filter({ hasText: '标签' })).toBeVisible()

  await page.locator('.ant-select-item-option').filter({ hasText: '日落海边与晚霞' }).first().click()

  // 点击建议后按该关键词重新查询（不含分页参数干扰）
  await expect
    .poll(() => listBodies[listBodies.length - 1]?.searchText)
    .toBe('日落海边与晚霞')
  // 命中的名称用 <mark> 高亮，且同时保存进搜索历史
  await expect(page.getByTestId('picture-name').first().locator('mark')).toHaveText('日落海边与晚霞')
  await expect(page.getByTestId('search-history-item').filter({ hasText: '日落海边与晚霞' })).toBeVisible()
})

test('首页搜索：空输入不请求联想，空结果显示热词引导', async ({ page }) => {
  const listBodies: Record<string, any>[] = []
  let suggestRequests = 0

  await mockSearchApi(page, {
    list: (body) => {
      listBodies.push(body)
      return { records: [], total: 0 }
    },
  })
  page.on('request', (request) => {
    if (request.url().includes('/search/suggest')) {
      suggestRequests += 1
    }
  })

  await page.goto('/')
  await expect(page.getByTestId('home-empty')).toBeVisible()
  // 首屏空输入不应该产生联想请求
  expect(suggestRequests).toBe(0)

  // 热词来自 /search/hot，点击后作为关键词搜索
  await expect(page.getByTestId('search-hot-item').filter({ hasText: '星空' })).toBeVisible()
  await page.getByTestId('search-hot-item').filter({ hasText: '星空' }).click()

  await expect.poll(() => listBodies[listBodies.length - 1]?.searchText).toBe('星空')
  await expect(page.getByTestId('home-empty')).toContainText('没有找到与「星空」相关的图片')
})

test('首页搜索：历史记录写入本地、刷新后仍在、可清空', async ({ page }) => {
  await mockSearchApi(page, {
    list: () => ({ records: [makePicture('1', '测试图片')], total: 1 }),
  })

  await page.goto('/')
  await searchInput(page).fill('蓝色')
  await page.getByTestId('search-submit').click()

  const historyItem = page.getByTestId('search-history-item').filter({ hasText: '蓝色' })
  await expect(historyItem).toBeVisible()

  // 刷新后从 localStorage 恢复
  await page.reload()
  await expect(page.getByTestId('search-history-item').filter({ hasText: '蓝色' })).toBeVisible()

  await page.getByTestId('search-history-clear').click()
  await expect(page.getByTestId('search-history')).toHaveCount(0)
  // 清空后刷新不再出现
  await page.reload()
  await expect(page.getByTestId('search-history')).toHaveCount(0)
})


test('详情页分享：弹窗展示链接与二维码，并写入 OG 元信息', async ({ page }) => {
  // 用桩替换剪贴板：headless 下 navigator.clipboard 依赖窗口焦点，直接读会不稳定
  await page.addInitScript(() => {
    const copied: string[] = []
    ;(window as unknown as { __copied: string[] }).__copied = copied
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: async (text: string) => void copied.push(text) },
    })
  })
  await page.route('**/mock-images/**', (route) =>
    route.fulfill({ body: MOCK_IMAGE, contentType: 'image/png' }),
  )
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: { code: 40100, data: null, message: '未登录' } })
      return
    }
    if (url.includes('/picture/get/vo')) {
      await route.fulfill({
        json: {
          code: 0,
          data: makePicture('1', '日落海边', { introduction: '夏天傍晚的海边' }),
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/interaction/status')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { favorite: false, like: false, favoriteCount: '1', likeCount: '1' },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      await route.fulfill({ json: { code: 0, data: { records: [], total: 0 }, message: 'ok' } })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({
        json: { code: 0, data: { tagList: [], categoryList: [] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/search/hot')) {
      await route.fulfill({
        json: { code: 0, data: { tags: [], categories: [] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/picture/1')

  // 人类访问时由前端动态写 title 与 og:*，规则与后端分享页一致
  await expect(page).toHaveTitle('日落海边 - 云图库')
  await expect(page.locator('meta[property="og:title"]')).toHaveAttribute(
    'content',
    '日落海边 - 云图库',
  )
  const ogImage = await page.locator('meta[property="og:image"]').getAttribute('content')
  // 分享封面固定用 JPEG，微信等平台对 WebP 支持不稳定
  expect(ogImage).toContain('imageMogr2/thumbnail/1200x/format/jpeg')

  await page.getByTestId('picture-share').click()
  await expect(page.getByTestId('share-modal')).toBeVisible()
  // a-input 没有前后缀时 data-testid 直接落在 <input> 上，这里两种结构都兼容
  const shareLinkInput = page.locator(
    'input[data-testid="share-link-input"], [data-testid="share-link-input"] input',
  )
  await expect(shareLinkInput).toHaveValue(/\/picture\/1$/)
  // 二维码由 qrcode 动态加载后生成
  await expect(page.getByTestId('share-qr').locator('img')).toBeVisible()

  await page.getByTestId('share-copy').click()
  await expect(page.getByText('链接已复制')).toBeVisible()
  const copied = await page.evaluate(
    () => (window as unknown as { __copied: string[] }).__copied,
  )
  expect(copied[0]).toContain('/picture/1')

  // 关闭弹窗后返回首页，验证动态 meta 被清理
  await page.getByTestId('share-modal').press('Escape')
  await expect(page.getByTestId('share-modal')).toBeHidden()
  await page.locator('.logo').click()
  await expect(page).toHaveTitle('云图库')
  await expect(page.locator('meta[property="og:title"]')).toHaveCount(0)
})