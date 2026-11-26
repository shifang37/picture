import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'
// 1x1 透明 PNG，避免测试依赖外网图片
const MOCK_IMAGE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

interface MockPicture {
  id: string
  url: string
  name: string
  category: string
  tags: string[]
  picWidth: number
  picHeight: number
  reviewStatus: number
}

const makePicture = (id: string, overrides: Partial<MockPicture> = {}): MockPicture => ({
  id,
  url: `https://example.invalid/mock-images/${id}.jpg`,
  name: `图片 ${id}`,
  category: '风景',
  tags: ['测试'],
  picWidth: 1200,
  picHeight: 800,
  reviewStatus: 1,
  ...overrides,
})

/**
 * 统一 Mock 首页依赖的接口，并让缩略图请求返回本地 1x1 图片。
 * listHandler 收到列表请求的 JSON body，返回 data 字段。
 */
const mockHomeApi = async (
  page: Page,
  listHandler: (body: Record<string, any>) => Record<string, any>,
  tagCategory: { tagList: string[]; categoryList: string[] } = {
    tagList: ['测试', '蓝色', '日落'],
    categoryList: ['风景', '人物'],
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
      await route.fulfill({
        json: { code: 0, data: listHandler(body), message: 'ok' },
      })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({ json: { code: 0, data: tagCategory, message: 'ok' } })
      return
    }
    if (url.includes('/picture/get/vo')) {
      await route.fulfill({ json: { code: 0, data: makePicture('1'), message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })
}

const mockDashboardApi = async (page: Page) => {
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { id: '1', userAccount: 'admin', userName: '管理员', userRole: 'admin' },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/overview')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            userCount: '1234',
            pictureCount: '567',
            pendingReviewCount: '8',
            totalPicSize: '1048576',
            totalDownloadCount: '9001',
          },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/upload_trend')) {
      await route.fulfill({
        json: {
          code: 0,
          data: [
            { date: '2026-11-09', count: '2' },
            { date: '2026-11-10', count: '5' },
            { date: '2026-11-11', count: '3' },
          ],
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/category')) {
      await route.fulfill({
        json: {
          code: 0,
          data: [
            { category: '风景', count: '10', totalSize: '2048' },
            { category: '人物', count: '4', totalSize: '1024' },
          ],
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/tags')) {
      await route.fulfill({
        json: {
          code: 0,
          data: [
            { tag: '测试', count: '7' },
            { tag: '蓝色', count: '3' },
          ],
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/review')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { approvedCount: '5', rejectedCount: '2', averageReviewSeconds: '90' },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/top_download')) {
      await route.fulfill({
        json: {
          code: 0,
          data: [
            {
              id: '1',
              name: '最热图片',
              url: 'https://example.invalid/1.jpg',
              downloadCount: '99',
            },
          ],
          message: 'ok',
        },
      })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })
}

test('未知路径显示 404 页面', async ({ page }) => {
  await page.route(API_PATTERN, (route) =>
    route.fulfill({ json: { code: 40100, data: null, message: '未登录' } }),
  )
  await page.goto('/not-exist-page')

  await expect(page.getByText('404', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回首页' })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回上一页' })).toBeVisible()
})

test('首页在接口 Mock 下正常渲染', async ({ page }) => {
  await mockHomeApi(page, () => ({ records: [], total: 0 }))

  await page.goto('/')

  await expect(page.getByText('shifang37云图库').first()).toBeVisible()
  // 首页改版后标签筛选收进筛选面板，标签标题不再带全角冒号
  await expect(page.locator('.tag-bar .filter-label')).toHaveText('标签')
  await expect(page.getByText('暂无图片，换个搜索条件试试')).toBeVisible()
})

test('首页卡片使用统一比例封面并保持网格齐平', async ({ page }) => {
  await mockHomeApi(page, () => ({
    records: [
      makePicture('1', { name: '横图', picWidth: 1200, picHeight: 800 }),
      makePicture('2', { name: '竖图', picWidth: 800, picHeight: 1200 }),
    ],
    total: 2,
  }))

  await page.goto('/')
  await expect(page.getByTestId('picture-card')).toHaveCount(2)
  await expect(page.getByText('横图')).toBeVisible()
  await expect(page.getByText('竖图')).toBeVisible()

  // 首页在 f20aa4b 改为等比例网格：封面统一 4:3，横图/竖图不再按原始宽高拉伸
  const ratios = await page
    .locator('.picture-cover')
    .evaluateAll((elements) => elements.map((element) => getComputedStyle(element).aspectRatio))
  expect(ratios).toHaveLength(2)
  for (const ratio of ratios) {
    expect(ratio).toBe('4 / 3')
  }
})

test('首页通过分页切换页码查看下一页并同步 URL', async ({ page }) => {
  const requestedPages: number[] = []
  await mockHomeApi(page, (body) => {
    const current = Number(body.current)
    requestedPages.push(current)
    if (current === 1) {
      return {
        records: Array.from({ length: 16 }, (_, index) => makePicture(String(index + 1))),
        total: 18,
      }
    }
    return {
      records: [
        makePicture('17', { name: '第二页图片 A' }),
        makePicture('18', { name: '第二页图片 B' }),
      ],
      total: 18,
    }
  })

  await page.goto('/')
  await expect(page.getByTestId('picture-card')).toHaveCount(16)
  await expect(page.getByTestId('home-pagination')).toContainText('共 18 张')

  await page.getByTestId('home-pagination').getByTitle('2', { exact: true }).click()

  await expect(page.getByText('第二页图片 A')).toBeVisible()
  await expect(page.getByTestId('picture-card')).toHaveCount(2)
  await expect(page).toHaveURL(/page=2/)
  expect(requestedPages).toEqual([1, 2])
})

test('首页从 URL 还原搜索词、分类和标签', async ({ page }) => {
  let lastBody: Record<string, any> | undefined
  await mockHomeApi(page, (body) => {
    lastBody = body
    return { records: [], total: 0 }
  })

  await page.goto(
    '/?searchText=%E6%B5%B7%E8%BE%B9&category=%E9%A3%8E%E6%99%AF&tags=%E8%93%9D%E8%89%B2&tags=%E6%97%A5%E8%90%BD',
  )

  // 搜索框换成 AutoComplete 后，placeholder 由 Ant Design 渲染成占位层而不是 input 属性，
  // 这里改为直接断言输入框的值与空结果引导文案
  await expect(page.getByTestId('search-input').locator('input')).toHaveValue('海边')
  await expect(page.getByTestId('home-empty')).toContainText('没有找到与「海边」相关的图片')
  expect(lastBody?.searchText).toBe('海边')
  expect(lastBody?.category).toBe('风景')
  expect(lastBody?.tags).toEqual(['蓝色', '日落'])
})

test('从详情页返回首页恢复原来的滚动位置', async ({ page }) => {
  await mockHomeApi(page, () => ({
    records: Array.from({ length: 20 }, (_, index) => makePicture(String(index + 1))),
    total: 20,
  }))

  await page.goto('/')
  await expect(page.getByTestId('picture-card')).toHaveCount(20)
  await page.evaluate(() => window.scrollTo(0, 600))

  await page.getByTestId('picture-card').nth(8).click()
  await expect(page).toHaveURL(/\/picture\/\d+$/)

  await page.goBack()
  await expect(page).toHaveURL(/\/$/)
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0)
})

test('首页不会请求管理端页面代码', async ({ page }) => {
  const scriptUrls: string[] = []
  page.on('request', (request) => {
    if (request.resourceType() === 'script') {
      scriptUrls.push(request.url())
    }
  })
  await mockHomeApi(page, () => ({ records: [], total: 0 }))

  await page.goto('/')
  await expect(page.getByText('shifang37云图库').first()).toBeVisible()

  expect(scriptUrls.some((url) => url.includes('/pages/admin/'))).toBe(false)
  expect(scriptUrls.some((url) => url.toLowerCase().includes('echarts'))).toBe(false)
  expect(scriptUrls.some((url) => url.includes('DashboardPage'))).toBe(false)
})

test('管理员进入管理页时才加载对应页面代码', async ({ page }) => {
  const scriptUrls: string[] = []
  page.on('request', (request) => {
    if (request.resourceType() === 'script') {
      scriptUrls.push(request.url())
    }
  })
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { id: '1', userAccount: 'admin', userName: '管理员', userRole: 'admin' },
          message: 'ok',
        },
      })
      return
    }
    await route.fulfill({ json: { code: 0, data: { records: [], total: 0 }, message: 'ok' } })
  })

  await page.goto('/admin/userManage')
  await expect(page).toHaveURL(/\/admin\/userManage$/)

  expect(scriptUrls.some((url) => url.includes('/pages/admin/UserManagePage.vue'))).toBe(true)
})

test('管理员进入数据看板才加载 ECharts 并展示统计结果', async ({ page }) => {
  const scriptUrls: string[] = []
  page.on('request', (request) => {
    if (request.resourceType() === 'script') {
      scriptUrls.push(request.url())
    }
  })
  await mockDashboardApi(page)

  await page.goto('/admin/dashboard')
  await expect(page.getByRole('heading', { name: '数据看板' })).toBeVisible()
  await expect(page.getByText('1,234')).toBeVisible()
  await expect(page.getByText('最热图片')).toBeVisible()
  await expect(page.locator('canvas')).toHaveCount(4)

  expect(scriptUrls.some((url) => url.includes('/pages/admin/DashboardPage.vue'))).toBe(true)
  expect(scriptUrls.some((url) => url.toLowerCase().includes('echarts'))).toBe(true)
})

test('未登录访问受限页面会跳转登录页并带 redirect', async ({ page }) => {
  await page.route(API_PATTERN, (route) =>
    route.fulfill({ json: { code: 40100, data: null, message: '未登录' } }),
  )

  const redirectOf = () => new URL(page.url()).searchParams.get('redirect')

  await page.goto('/my_pictures')
  await expect(page).toHaveURL(/\/user\/login/)
  await expect.poll(redirectOf).toBe('/my_pictures')

  await page.goto('/admin/userManage')
  await expect(page).toHaveURL(/\/user\/login/)
  await expect.poll(redirectOf).toBe('/admin/userManage')

  await page.goto('/admin/dashboard')
  await expect(page).toHaveURL(/\/user\/login/)
  await expect.poll(redirectOf).toBe('/admin/dashboard')
})
