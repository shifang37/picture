import { expect, test, type Page } from '@playwright/test'

const API_PATTERN = 'http://localhost:5173/api/**'

const RAG_CONFIG_ON = {
  enabled: true,
  hybridSearch: true,
  askEnabled: true,
  askDailyLimit: 10,
  askTopK: 8,
}

const RAG_CONFIG_OFF = {
  enabled: false,
  hybridSearch: false,
  askEnabled: false,
  askDailyLimit: 10,
  askTopK: 8,
}

const LOGIN_USER = {
  id: '1',
  userAccount: 'e2e-admin',
  userName: 'E2E 管理员',
  userRole: 'admin',
  userAvatar: '',
}

const makePicture = (id: string, name: string) => ({
  id,
  url: `https://example.invalid/mock-images/${id}.jpg`,
  thumbnailUrl: `https://example.invalid/mock-images/${id}.jpg`,
  name,
  introduction: 'RAG E2E 图片',
  category: '风景',
  tags: ['测试'],
  picWidth: 1200,
  picHeight: 800,
  reviewStatus: 1,
  favoriteCount: '0',
  likeCount: '0',
  downloadCount: '0',
})

/**
 * 统一的 API Mock：只处理本文件关心的接口，其余返回 code=0 的空数据。
 * `ragConfig` 决定首页 / 问答入口 / 看板状态卡是否出现。
 */
const mockApi = async (
  page: Page,
  options: {
    ragConfig?: Record<string, unknown>
    ask?: Record<string, unknown>
    askCode?: number
    status?: Record<string, unknown>
    listTotal?: number
    listRecords?: Record<string, unknown>[]
  } = {},
) => {
  const listBodies: any[] = []
  const askBodies: any[] = []

  await page.route('**/mock-images/**', (route) =>
    route.fulfill({ status: 404, body: 'not used' }),
  )
  await page.route(API_PATTERN, async (route) => {
    const url = route.request().url()
    const request = route.request()

    if (url.includes('/user/get/login')) {
      await route.fulfill({ json: { code: 0, data: LOGIN_USER, message: 'ok' } })
      return
    }
    if (url.includes('/rag/config')) {
      await route.fulfill({
        json: { code: 0, data: options.ragConfig ?? RAG_CONFIG_ON, message: 'ok' },
      })
      return
    }
    if (url.includes('/rag/status')) {
      await route.fulfill({
        json: {
          code: 0,
          data:
            options.status ??
            {
              enabled: true,
              textModel: 'text-embedding-v4',
              imageModel: 'multimodal-embedding-v1',
              dims: 1024,
              indexName: 'picture_vec_v1',
              alias: 'picture_vec',
              version: 'v1',
              textVersion: 'v1',
              docCount: 108,
              pendingCount: 2,
              failedCount: 1,
              stale: false,
            },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/rag/ask')) {
      askBodies.push(JSON.parse(request.postData() || '{}'))
      await route.fulfill({
        json: {
          code: options.askCode ?? 0,
          data: options.ask ?? {
            answer: '推荐咖啡 09：竖版、极简构图。',
            citations: [
              { id: '2121201825714593793', name: '咖啡 09', thumbnailUrl: '', reason: '竖版' },
            ],
            refused: false,
            degraded: false,
          },
          message: options.askCode ? '今日问答次数已用完（每天 10 次），请明天再试' : 'ok',
        },
      })
      return
    }
    if (url.includes('/picture/list/page/vo')) {
      listBodies.push(JSON.parse(request.postData() || '{}'))
      await route.fulfill({
        json: {
          code: 0,
          data: {
            current: 1,
            pageSize: 20,
            total: options.listTotal ?? 0,
            records: options.listRecords ?? [],
          },
          message: 'ok',
        },
      })
      return
    }
    if (url.includes('/picture/tag_category')) {
      await route.fulfill({
        json: { code: 0, data: { tagList: ['夜景'], categoryList: ['风景'] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/search/hot')) {
      await route.fulfill({
        json: { code: 0, data: { tags: [], categories: [] }, message: 'ok' },
      })
      return
    }
    if (url.includes('/notification/unread-count')) {
      await route.fulfill({ json: { code: 0, data: { count: 0 }, message: 'ok' } })
      return
    }
    // 看板 6 个 analyze 接口：用空数据即可，避免测试互相依赖
    await route.fulfill({ json: { code: 0, data: null, message: 'ok' } })
  })

  return { listBodies, askBodies }
}

test('首页：RAG 开启时带 searchMode=hybrid，并展示语义检索与问答入口', async ({ page }) => {
  const { listBodies } = await mockApi(page)

  await page.goto('/')

  await expect(page.getByTestId('hybrid-tip')).toBeVisible()
  await expect(page.getByTestId('ask-entry')).toBeVisible()
  await expect(page.getByTestId('search-input')).toContainText('描述你想要的图')

  await page.getByTestId('search-input').locator('input').fill('暖色调的日落海边照片')
  await page.getByTestId('search-submit').click()

  await expect.poll(() => listBodies.length).toBeGreaterThan(1)
  expect(listBodies.at(-1)?.searchText).toBe('暖色调的日落海边照片')
  expect(listBodies.at(-1)?.searchMode).toBe('hybrid')
})

test('首页：RAG 关闭时不传 searchMode，页面与改造前一致', async ({ page }) => {
  const { listBodies } = await mockApi(page, { ragConfig: RAG_CONFIG_OFF })

  await page.goto('/')

  await expect(page.getByTestId('hybrid-tip')).toHaveCount(0)
  await expect(page.getByTestId('ask-entry')).toHaveCount(0)
  await expect(page.getByTestId('search-input')).toContainText('从海量图片中搜索')

  await page.getByTestId('search-input').locator('input').fill('海边')
  await page.getByTestId('search-submit').click()

  await expect.poll(() => listBodies.length).toBeGreaterThan(1)
  expect(listBodies.at(-1)?.searchText).toBe('海边')
  expect(listBodies.at(-1)?.searchMode).toBeUndefined()
})

test('问答：正常回答展示引用卡片，点击可进入图片详情', async ({ page }) => {
  const { askBodies } = await mockApi(page)

  await page.goto('/ask')
  await page.getByTestId('ask-input').fill('哪些图片适合做竖版海报？')
  await page.getByTestId('ask-submit').click()

  await expect(page.getByTestId('ask-answer')).toContainText('推荐咖啡 09')
  await expect(page.getByTestId('ask-citations')).toContainText('咖啡 09')
  await expect(page.getByTestId('citation-2121201825714593793')).toHaveAttribute(
    'href',
    '/picture/2121201825714593793',
  )
  expect(askBodies[0]?.question).toBe('哪些图片适合做竖版海报？')
})

test('问答：无相关图片时展示拒答态且没有引用', async ({ page }) => {
  await mockApi(page, {
    ask: {
      answer: '没有找到与问题相关的图片。',
      citations: [],
      refused: true,
      degraded: false,
    },
  })

  await page.goto('/ask')
  await page.getByTestId('ask-input').fill('赛博朋克霓虹街景')
  await page.getByTestId('ask-submit').click()

  await expect(page.getByTestId('ask-refused')).toBeVisible()
  await expect(page.getByTestId('ask-citations')).toHaveCount(0)
  await expect(page.getByTestId('ask-error')).toHaveCount(0)
})

test('问答：超限 42900 时展示明确的限额提示，不展示回答', async ({ page }) => {
  await mockApi(page, { askCode: 42900 })

  await page.goto('/ask')
  await page.getByTestId('ask-input').fill('还有哪些海报图？')
  await page.getByTestId('ask-submit').click()

  await expect(page.getByTestId('ask-error')).toContainText('今日问答次数已用完')
  await expect(page.getByTestId('ask-answer')).toHaveCount(0)
})

test('看板：RAG 状态卡展示索引数量、待处理/失败与模型版本', async ({ page }) => {
  await mockApi(page)

  await page.goto('/admin/dashboard')

  const card = page.getByTestId('rag-status-card')
  await expect(card).toBeVisible()
  await expect(card).toContainText('108')
  await expect(card).toContainText('待处理')
  await expect(card).toContainText('text-embedding-v4')
  await expect(card).toContainText('picture_vec_v1')
  await expect(card.getByTestId('rag-reindex')).toBeVisible()
})
