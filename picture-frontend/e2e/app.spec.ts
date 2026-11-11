import { expect, test } from '@playwright/test'

test('未知路径显示 404 页面', async ({ page }) => {
  await page.route('http://localhost:5173/api/**', (route) =>
    route.fulfill({ json: { code: 40100, data: null, message: '未登录' } }),
  )
  await page.goto('/not-exist-page')

  await expect(page.getByText('404', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回首页' })).toBeVisible()
  await expect(page.getByRole('button', { name: '返回上一页' })).toBeVisible()
})

test('首页在接口 Mock 下正常渲染', async ({ page }) => {
  await page.route('http://localhost:5173/api/**', async (route) => {
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
