import { expect, test } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'

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
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: { code: 40100, data: null, message: '未登录' } })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      await route.fulfill({
        json: { code: 0, data: { records: [], total: 0 }, message: 'ok' },
      })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({
        json: { code: 0, data: { tagList: [], categoryList: [] }, message: 'ok' },
      })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/')

  await expect(page.getByText('鱼皮云图库').first()).toBeVisible()
  await expect(page.getByText('标签：')).toBeVisible()
})

test('首页不会请求管理端页面代码', async ({ page }) => {
  const scriptUrls: string[] = []
  page.on('request', (request) => {
    if (request.resourceType() === 'script') {
      scriptUrls.push(request.url())
    }
  })
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: { code: 40100, data: null, message: '未登录' } })
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
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/')
  await expect(page.getByText('鱼皮云图库').first()).toBeVisible()

  expect(scriptUrls.some((url) => url.includes('/pages/admin/'))).toBe(false)
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
})
