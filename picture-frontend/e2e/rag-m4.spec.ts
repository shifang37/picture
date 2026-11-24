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
  introduction: '相似图 E2E 图片',
  category: '风景',
  tags: ['测试'],
  picWidth: 1200,
  picHeight: 800,
  reviewStatus: 1,
  favoriteCount: '0',
  likeCount: '0',
  downloadCount: '0',
  ...overrides,
})

/**
 * 详情页与相似图接口的公共 Mock：get/vo 按 id 返回不同图片，similar 由回调决定。
 */
const mockDetailApi = async (
  page: Page,
  options: {
    similar: (pictureId: string) => Record<string, unknown>[]
    similarStatus?: number
  },
) => {
  const similarRequests: string[] = []
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
      const id = new URL(url).searchParams.get('id') ?? '1'
      await route.fulfill({ json: { code: 0, data: makePicture(id, `图片 ${id}`), message: 'ok' } })
      return
    }
    if (url.includes('/picture/similar')) {
      similarRequests.push(url)
      const id = new URL(url).searchParams.get('id') ?? '1'
      await route.fulfill({
        json: {
          code: options.similarStatus ?? 0,
          data: options.similarStatus ? null : options.similar(id),
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/interaction/status')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { favorite: false, like: false, favoriteCount: '0', likeCount: '0' },
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
  return { similarRequests }
}

test('相似图：详情页展示 6 宫格，点击后切换详情并重新拉取相似图', async ({ page }) => {
  const { similarRequests } = await mockDetailApi(page, {
    similar: (id) =>
      id === '1'
        ? [makePicture('2', '相似图二'), makePicture('3', '相似图三')]
        : [makePicture('1', '相似图一')],
  })

  await page.goto('/picture/1')

  await expect(page.getByTestId('similar-section')).toBeVisible()
  await expect(page.getByTestId('similar-section')).toContainText('相似图片')
  await expect(page.getByTestId('similar-item-2')).toBeVisible()
  await expect(page.getByTestId('similar-item-3')).toBeVisible()
  expect(similarRequests[0]).toContain('limit=6')

  await page.getByTestId('similar-item-2').click()

  await expect(page).toHaveURL(/\/picture\/2$/)
  await expect(page.getByTestId('similar-item-1')).toBeVisible()
  // 路由参数变化后必须重新请求：id=1 与 id=2 各一次
  expect(similarRequests.some((url) => url.includes('id=2'))).toBe(true)
})

test('相似图：接口返回空数组时不渲染区块，详情页其他内容正常', async ({ page }) => {
  await mockDetailApi(page, { similar: () => [] })

  await page.goto('/picture/1')

  await expect(page.getByTestId('picture-share')).toBeVisible()
  await expect(page.getByTestId('similar-section')).toHaveCount(0)
})

test('相似图：接口失败时静默降级，不出现错误弹窗也不渲染区块', async ({ page }) => {
  await mockDetailApi(page, { similar: () => [], similarStatus: 50000 })

  await page.goto('/picture/1')

  await expect(page.getByTestId('picture-share')).toBeVisible()
  await expect(page.getByTestId('similar-section')).toHaveCount(0)
  await expect(page.locator('.ant-message-error')).toHaveCount(0)
})
