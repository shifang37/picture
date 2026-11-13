import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'

/**
 * 管理端看板：只 Mock 看板与运维接口，页面其余请求一律返回成功空数据。
 * ops 里按路径给出运维接口的响应，便于分别验证成功与失败分支。
 */
const mockDashboardApi = async (
  page: Page,
  ops: {
    reindex?: Record<string, any>
    interaction?: Record<string, any>
    album?: Record<string, any>
  },
) => {
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
    if (url.includes('/search/reindex')) {
      await route.fulfill({ json: ops.reindex ?? { code: 0, data: 0, message: 'ok' } })
      return
    }
    if (url.includes('/interaction/recount')) {
      await route.fulfill({ json: ops.interaction ?? { code: 0, data: 0, message: 'ok' } })
      return
    }
    if (url.includes('/album/recount')) {
      await route.fulfill({ json: ops.album ?? { code: 0, data: 0, message: 'ok' } })
      return
    }
    if (url.includes('/analyze/overview')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            userCount: '3',
            pictureCount: '12',
            pendingReviewCount: '1',
            totalPicSize: '2048',
            totalDownloadCount: '7',
          },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/analyze/upload_trend')) {
      await route.fulfill({ json: { code: 0, data: [], message: 'ok' } })
      return
    }
    if (url.includes('/analyze/category')) {
      await route.fulfill({ json: { code: 0, data: [], message: 'ok' } })
      return
    }
    if (url.includes('/analyze/tags')) {
      await route.fulfill({ json: { code: 0, data: [], message: 'ok' } })
      return
    }
    if (url.includes('/analyze/review')) {
      await route.fulfill({
        json: { code: 0, data: { approvedCount: '0', rejectedCount: '0' }, message: 'ok' },
      })
      return
    }
    if (url.includes('/analyze/top_download')) {
      await route.fulfill({ json: { code: 0, data: [], message: 'ok' } })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })
}

test('数据看板：运维入口需二次确认后才调用接口', async ({ page }) => {
  const calls: string[] = []
  page.on('request', (request) => {
    const url = request.url()
    if (!url.startsWith('http://localhost:5173/api/')) return
    if (url.includes('/interaction/recount')) calls.push('interaction')
    if (url.includes('/album/recount')) calls.push('album')
    if (url.includes('/search/reindex')) calls.push('reindex')
  })
  await mockDashboardApi(page, {
    reindex: { code: 0, data: 12, message: 'ok' },
    interaction: { code: 0, data: 34, message: 'ok' },
    album: { code: 0, data: 5, message: 'ok' },
  })

  await page.goto('/admin/dashboard')
  await expect(page.getByRole('heading', { name: '数据看板' })).toBeVisible()

  // 只点按钮不确认时不应发请求
  await page.getByRole('button', { name: '重算互动计数' }).click()
  await expect(
    page.getByText('按关系表重算所有图片的收藏数与点赞数，不会改动用户数据'),
  ).toBeVisible()
  expect(calls).toEqual([])

  await page.getByRole('button', { name: '重算互动', exact: true }).click()
  await expect(page.getByText('互动计数已重算，共更新 34 张图片')).toBeVisible()

  await page.getByRole('button', { name: '重算专辑计数' }).click()
  await page.getByRole('button', { name: '重算专辑', exact: true }).click()
  await expect(page.getByText('专辑计数已重算，共更新 5 个专辑')).toBeVisible()

  await page.getByRole('button', { name: '重建搜索索引' }).click()
  await page.getByRole('button', { name: '重建索引', exact: true }).click()
  await expect(page.getByText('索引已重建，共写入 12 张图片')).toBeVisible()

  expect(calls).toEqual(['interaction', 'album', 'reindex'])
})

test('数据看板：搜索未启用时重建索引给出后端错误提示', async ({ page }) => {
  await mockDashboardApi(page, {
    reindex: { code: 50000, data: null, message: '未启用 Elasticsearch 搜索' },
  })

  await page.goto('/admin/dashboard')
  await page.getByRole('button', { name: '重建搜索索引' }).click()
  await page.getByRole('button', { name: '重建索引', exact: true }).click()

  await expect(page.getByText('重建索引失败：未启用 Elasticsearch 搜索')).toBeVisible()
})
