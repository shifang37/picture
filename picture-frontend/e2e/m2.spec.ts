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

test('我的图片：批量编辑单次提交选中图片与标签', async ({ page }) => {
  let batchEditBody: Record<string, unknown> | undefined

  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/picture/my/page/vo')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            records: [mockPicture({ id: '1' }), mockPicture({ id: '2', name: '第二张' })],
            total: 2,
          },
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
    if (url.includes('/picture/batch/edit')) {
      batchEditBody = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        json: { code: 0, data: { successCount: 2, failCount: 0, errors: [] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/my_pictures')
  await expect(page.getByRole('heading', { name: '我的图片' })).toBeVisible()

  // 全选当前页
  await page.locator('.ant-table-thead .ant-checkbox-wrapper').click()
  await expect(page.getByText('已选择 2 张')).toBeVisible()
  await page.getByRole('button', { name: '批量编辑' }).click()

  // 在标签选择框里输入一个新标签（mode=tags 回车即新增）
  const tagSelect = page
    .locator('.ant-form-item')
    .filter({ hasText: '标签' })
    .locator('.ant-select')
  await tagSelect.click()
  await page.keyboard.type('旅行')
  await page.keyboard.press('Enter')
  // Ant Design 会在两个汉字的按钮文字之间插入空格，可访问名是「保 存」
  await page.getByRole('button', { name: /保\s*存/ }).click()

  await expect(page.getByText('已更新 2 张图片')).toBeVisible()
  expect(batchEditBody?.ids).toEqual(['1', '2'])
  expect(batchEditBody?.tags).toEqual(['旅行'])
  // 未填写的字段不提交，避免误覆盖
  expect(batchEditBody?.category).toBeUndefined()
  expect(batchEditBody?.introduction).toBeUndefined()
})

test('我的图片：批量删除选中图片并刷新列表', async ({ page }) => {
  let batchDeleteBody: Record<string, unknown> | undefined

  await mockImages(page)
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/picture/my/page/vo')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            records: [mockPicture({ id: '1' }), mockPicture({ id: '2', name: '第二张' })],
            total: 2,
          },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/picture/batch/delete')) {
      batchDeleteBody = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        json: { code: 0, data: { successCount: 2, failCount: 0, errors: [] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/my_pictures')
  await page.locator('.ant-table-thead .ant-checkbox-wrapper').click()
  await page.getByRole('button', { name: '批量删除' }).click()
  // 二次确认的「删除」只在批量删除的 Popconfirm 里，避免和表格行内删除按钮重名
  await page
    .getByRole('tooltip')
    .getByRole('button', { name: /删\s*除/ })
    .click()

  await expect(page.getByText('已删除 2 张图片')).toBeVisible()
  expect(batchDeleteBody?.ids).toEqual(['1', '2'])
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
