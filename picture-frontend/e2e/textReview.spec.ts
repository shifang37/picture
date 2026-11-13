import { expect, test } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'

/**
 * 审核页：文审列展示状态标签，悬浮可看命中标签、置信度与文审时间。
 * 只 Mock 审核列表与登录态，其余接口返回成功空数据。
 */
test('审核页：展示文审状态与详情', async ({ page }) => {
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
    if (url.includes('/picture/list/page')) {
      await route.fulfill({
        json: {
          code: 0,
          data: {
            records: [
              {
                id: '101',
                url: 'https://example.invalid/mock-images/101.jpg',
                name: '带文审结果的图片',
                introduction: '简介',
                category: '风景',
                tags: '["测试"]',
                reviewStatus: 0,
                machineReviewStatus: 0,
                machineReviewLabel: 'Normal',
                textReviewStatus: 1,
                textReviewLabel: 'Porn',
                textReviewScore: 88,
                textReviewTime: '2026-11-13T10:00:00',
                createTime: '2026-11-13T09:00:00',
              },
            ],
            total: 1,
          },
          message: 'ok',
        },
      })
      return
    }
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  await page.goto('/admin/pictureReview')
  await expect(page.getByRole('heading', { name: '图片审核' })).toBeVisible()
  await expect(page.getByText('带文审结果的图片')).toBeVisible()

  const textTag = page.getByText('文审违规')
  await expect(textTag).toBeVisible()

  // 悬浮展示命中标签、置信度与文审时间
  await textTag.hover()
  await expect(page.getByText('标签：Porn')).toBeVisible()
  await expect(page.getByText('置信度：88')).toBeVisible()
  await expect(page.getByText(/文审时间：/)).toBeVisible()
})
