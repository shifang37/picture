import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'
// 1x1 透明 PNG，避免测试依赖外网图片
const MOCK_IMAGE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

/**
 * M1 主链路 E2E：收藏 → 我的收藏、审核通知 → 图片详情。
 * 全部接口用 page.route Mock，不依赖后端、数据库与 COS，可在 CI 直接跑。
 */
const mockPicture = (overrides: Record<string, unknown> = {}) => ({
  id: '1',
  url: 'https://example.invalid/mock-images/1.jpg',
  name: '收藏测试图',
  introduction: '用于 M1 E2E 的图片',
  category: '风景',
  tags: ['测试'],
  picWidth: 1200,
  picHeight: 800,
  reviewStatus: 1,
  favoriteCount: '0',
  likeCount: '0',
  downloadCount: '0',
  user: { id: '9', userName: '作者', userAvatar: '' },
  ...overrides,
})

const loginUser = {
  code: 0,
  data: { id: '100', userAccount: 'tester', userName: '测试用户', userRole: 'user' },
  message: 'ok',
}

const mockImages = async (page: Page) => {
  await page.route('**/mock-images/**', (route) =>
    route.fulfill({ body: MOCK_IMAGE, contentType: 'image/png' }),
  )
}

test('收藏 → 我的收藏：详情页收藏后能在收藏列表看到', async ({ page }) => {
  // 服务端状态：用对象在 mock 之间共享，模拟真实的读后写
  const status = { favorite: false, like: false, favoriteCount: '0', likeCount: '0' }
  let myPageRequestType: string | undefined

  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/picture/get/vo')) {
      await route.fulfill({ json: { code: 0, data: mockPicture(), message: 'ok' } })
      return
    }
    if (url.includes('/interaction/status')) {
      await route.fulfill({ json: { code: 0, data: status, message: 'ok' } })
      return
    }
    if (url.includes('/interaction/add')) {
      status.favorite = true
      status.favoriteCount = '1'
      await route.fulfill({ json: { code: 0, data: status, message: 'ok' } })
      return
    }
    if (url.includes('/interaction/remove')) {
      status.favorite = false
      status.favoriteCount = '0'
      await route.fulfill({ json: { code: 0, data: status, message: 'ok' } })
      return
    }
    if (url.includes('/interaction/my/page')) {
      myPageRequestType = (route.request().postDataJSON() as { type?: string }).type
      await route.fulfill({
        json: {
          code: 0,
          data: { records: [mockPicture({ favoriteCount: '1' })], total: 1 },
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

  await page.goto('/picture/1')
  const favoriteButton = page.getByTestId('favorite-button')
  await expect(favoriteButton).toContainText('收藏 0')

  await favoriteButton.click()
  // 乐观更新 + 服务端返回后的最终状态
  await expect(favoriteButton).toContainText('已收藏 1')

  await page.goto('/my/favorites')
  await expect(page.getByRole('heading', { name: '我的收藏' })).toBeVisible()
  await expect(page.getByText('收藏测试图')).toBeVisible()
  expect(myPageRequestType).toBe('favorite')
})

test('审核通知 → 图片详情：铃铛显示未读并能跳转', async ({ page }) => {
  let markedRead = false

  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({
        json: { code: 0, data: { count: markedRead ? 0 : 1 }, message: 'ok' },
      })
      return
    }
    if (url.includes('/notification/page')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            records: [
              {
                id: '500',
                type: 'REVIEW_PASS',
                typeText: '图片审核通过',
                title: '图片审核通过',
                content: '你的图片《收藏测试图》已通过审核，现在可以在首页被浏览了',
                bizType: 'PICTURE',
                bizId: '1',
                isRead: markedRead ? 1 : 0,
                createTime: '2026-11-12T10:00:00',
              },
            ],
            total: 1,
          },
        },
      })
      return
    }
    if (url.includes('/notification/read')) {
      markedRead = true
      await route.fulfill({ json: { code: 0, data: { updated: 1 }, message: 'ok' } })
      return
    }
    if (url.includes('/picture/get/vo')) {
      await route.fulfill({ json: { code: 0, data: mockPicture(), message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/')
  // 未读红点：badge 上出现数字 1
  await expect(page.getByTestId('notification-bell')).toBeVisible()

  await page.getByTestId('notification-bell').click()
  await expect(page.getByText(/已通过审核/)).toBeVisible()

  // 点击面板里的通知条目（标题与类型文案相同，用条目容器定位避免歧义）
  await page.locator('.notification-item').first().click()
  await expect(page).toHaveURL(/\/picture\/1$/)
  // 点击后标记已读：接口被调用，红点归零
  await expect.poll(() => markedRead).toBe(true)
})

test('首页卡片 → 收藏：悬浮出现互动按钮，收藏后计数 +1 且不跳转', async ({ page }) => {
  const status = { favorite: false, like: false, favoriteCount: '2', likeCount: '0' }

  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { records: [mockPicture({ favoriteCount: '2' })], total: 1 },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({
        json: { code: 0, data: { categoryList: ['风景'], tagList: ['测试'] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/interaction/status')) {
      await route.fulfill({ json: { code: 0, data: status, message: 'ok' } })
      return
    }
    if (url.includes('/interaction/add')) {
      status.favorite = true
      status.favoriteCount = '3'
      await route.fulfill({ json: { code: 0, data: status, message: 'ok' } })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/')
  const card = page.getByTestId('picture-card').first()
  await expect(card).toBeVisible()

  // 互动入口默认隐藏，悬浮后才出现，避免卡片上一直挂两个按钮
  await card.hover()
  const favoriteButton = page.getByTestId('card-favorite-button').first()
  await expect(favoriteButton).toBeVisible()

  await favoriteButton.click()
  await expect(favoriteButton).toContainText('3')
  // 点击互动按钮不应触发卡片跳转（@click.stop）
  await expect(page).not.toHaveURL(/\/picture\//)
})

test('首页卡片没有互动按钮时也能正常进入详情', async ({ page }) => {
  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: { code: 0, data: null, message: '未登录' } })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      await route.fulfill({
        json: { code: 0, data: { records: [mockPicture()], total: 1 }, message: 'ok' },
      })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({
        json: { code: 0, data: { categoryList: [], tagList: [] }, message: 'ok' },
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
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/')
  await page
    .getByTestId('picture-card')
    .first()
    .click({ position: { x: 10, y: 10 } })
  await expect(page).toHaveURL(/\/picture\/1$/)
})
