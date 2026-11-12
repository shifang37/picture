import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'
// 1x1 透明 PNG，避免测试依赖外网图片
const MOCK_IMAGE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

/**
 * M2 主链路 E2E：作者主页、批量编辑、专辑。
 * 全部接口用 page.route Mock，不依赖后端、数据库与 COS，可在 CI 直接跑。
 */
const mockPicture = (overrides: Record<string, unknown> = {}) => ({
  id: '1',
  url: 'https://example.invalid/mock-images/1.jpg',
  name: '作者作品',
  introduction: '用于 M2 E2E 的图片',
  category: '风景',
  tags: ['测试'],
  picWidth: 1200,
  picHeight: 800,
  reviewStatus: 1,
  favoriteCount: '5',
  likeCount: '3',
  downloadCount: '10',
  user: { id: '9', userName: '作者甲', userAvatar: '' },
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

test('作者主页：详情页作者入口 → 主页统计与作品', async ({ page }) => {
  let pictureListQuery: Record<string, unknown> | undefined

  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/user/public/9')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            user: { id: '9', userName: '作者甲', userAvatar: '', userProfile: '专注风景' },
            pictureCount: '12',
            downloadCount: '340',
            favoriteCount: '56',
            likeCount: '88',
            joinedAt: '2026-01-01T10:00:00',
          },
        },
      })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      pictureListQuery = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        json: { code: 0, data: { records: [mockPicture()], total: 1 }, message: 'ok' },
      })
      return
    }
    if (url.includes('/picture/get/vo')) {
      await route.fulfill({ json: { code: 0, data: mockPicture(), message: 'ok' } })
      return
    }
    if (url.includes('/interaction/status')) {
      await route.fulfill({
        json: {
          code: 0,
          data: { favorite: false, like: false, favoriteCount: '5', likeCount: '3' },
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

  await page.goto('/picture/1')
  await page.getByTestId('picture-author-link').click()

  await expect(page).toHaveURL(/\/author\/9$/)
  await expect(page.getByTestId('author-name')).toContainText('作者甲')
  await expect(page.getByTestId('author-picture-count')).toContainText('12')
  await expect(page.getByText('专注风景')).toBeVisible()
  // 作品列表按作者过滤，且按创建时间倒序
  expect(pictureListQuery?.userId).toBe('9')
  expect(pictureListQuery?.sortField).toBe('createTime')
  await expect(page.getByTestId('picture-card').first()).toBeVisible()
})

test('作者主页：作者不存在展示 404', async ({ page }) => {
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/user/public/404')) {
      await route.fulfill({ json: { code: 40400, data: null, message: '用户不存在' } })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/author/404')
  await expect(page.getByTestId('author-not-found')).toBeVisible()
  await expect(page.getByText('作者不存在')).toBeVisible()
})

test('作者主页：没有公开作品展示空状态', async ({ page }) => {
  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/user/public/9')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            user: { id: '9', userName: '作者甲', userAvatar: '' },
            pictureCount: '0',
            downloadCount: '0',
            favoriteCount: '0',
            likeCount: '0',
            joinedAt: '2026-01-01T10:00:00',
          },
        },
      })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      await route.fulfill({ json: { code: 0, data: { records: [], total: 0 }, message: 'ok' } })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/author/9')
  await expect(page.getByTestId('author-empty')).toBeVisible()
  await expect(page.getByText('TA 还没有公开作品')).toBeVisible()
})
