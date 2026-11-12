import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'
// 1x1 透明 PNG，避免测试依赖外网图片
const MOCK_IMAGE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
  'base64',
)

const loginUser = {
  code: 0,
  data: { id: '100', userAccount: 'tester', userName: '测试用户', userRole: 'user' },
  message: 'ok',
}

const normalQuota = {
  storageUsed: '1048576',
  storageQuota: '1073741824',
  pictureUsed: 0,
  pictureQuota: 1000,
  exempt: false,
}

const exhaustedQuota = {
  storageUsed: '1073741824',
  storageQuota: '1073741824',
  pictureUsed: 0,
  pictureQuota: 1000,
  exempt: false,
}

interface MockOptions {
  quota?: Record<string, unknown>
  upload?: Record<string, unknown>
}

/**
 * M4 上传主链路 E2E：全部接口用 page.route Mock，不依赖后端与 COS。
 * 覆盖「配额超限在客户端被拦住」与「秒传命中给出复用提示」两条关键体验。
 */
const mockUploadApi = async (page: Page, options: MockOptions = {}) => {
  const state = { uploadRequests: 0 }
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: loginUser })
      return
    }
    if (url.includes('/user/quota')) {
      await route.fulfill({
        json: { code: 0, data: options.quota ?? normalQuota, message: 'ok' },
      })
      return
    }
    if (url.includes('/picture/upload')) {
      state.uploadRequests++
      await route.fulfill({
        json: {
          code: 0,
          data: options.upload ?? {
            id: '9',
            url: 'https://example.invalid/mock-images/9.jpg',
            name: 'upload',
            reviewStatus: 1,
          },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({ json: { code: 0, data: { tags: [], categories: [] }, message: 'ok' } })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })
  return state
}

test('配额超限：客户端直接拦住上传，不发起请求', async ({ page }) => {
  const state = await mockUploadApi(page, { quota: exhaustedQuota })

  await page.goto('/add_picture')
  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'photo.png',
    mimeType: 'image/png',
    buffer: MOCK_IMAGE,
  })

  await expect(page.getByText(/存储空间不足/)).toBeVisible()
  expect(state.uploadRequests).toBe(0)
})

test('秒传：命中相同文件时提示已复用，不重复占用空间', async ({ page }) => {
  const state = await mockUploadApi(page, {
    upload: {
      id: '9',
      url: 'https://example.invalid/mock-images/9.jpg',
      name: 'photo',
      reviewStatus: 1,
      duplicated: true,
    },
  })

  await page.goto('/add_picture')
  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'photo.png',
    mimeType: 'image/png',
    buffer: MOCK_IMAGE,
  })

  await expect(page.getByText(/已存在相同图片/)).toBeVisible()
  expect(state.uploadRequests).toBe(1)
})
