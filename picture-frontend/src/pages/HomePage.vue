<template>
  <div id="homePage">
    <!-- 首屏：简洁有力的策展标题 + 胶囊搜索框 -->
    <section class="hero">
      <div class="hero-eyebrow">
        <span class="hero-eyebrow-dot" aria-hidden="true"></span>
        <span>SHIFANG37 · 精选视觉素材库</span>
      </div>
      <h1 class="hero-title">收藏每一个<span class="hero-accent">值得被看见</span>的画面</h1>
      <p class="hero-sub">上传、策展、语义检索与分享高质量图片素材，让每一次灵感查找都恰如其分</p>
      <div class="search-bar">
        <SearchBox
          v-model="searchText"
          :placeholder="searchPlaceholder"
          :hot-tags="hotTagList"
          :hot-categories="hotCategoryList"
          @search="handleSearchBoxSearch"
        />
        <!-- RAG 开启时才出现的语义检索提示；关闭时页面与改造前完全一致 -->
        <div v-if="ragConfig.hybridSearch" class="search-mode-tip" data-testid="hybrid-tip">
          <span class="hybrid-pill">
            <span class="hybrid-dot" aria-hidden="true"></span>
            语义检索已开启
          </span>
          <span>试试描述光线、氛围、构图或使用场景</span>
          <router-link
            v-if="ragConfig.askEnabled"
            to="/ask"
            class="hybrid-ask-link"
            data-testid="ask-entry"
          >
            用一句话问图库 →
          </router-link>
        </div>
      </div>
    </section>

    <!-- 分类 / 排序 / 标签：精致画廊筛选栏 -->
    <section class="filters">
      <div class="filters-top">
        <div class="filters-categories">
          <a-tabs v-model:activeKey="selectedCategory" class="category-tabs" @change="doSearch">
            <a-tab-pane key="all" tab="全部" />
            <a-tab-pane v-for="category in categoryList" :key="category" :tab="category" />
          </a-tabs>
        </div>
        <div class="sort-bar">
          <span class="filter-label">排序</span>
          <a-radio-group
            v-model:value="sortField"
            size="small"
            button-style="solid"
            @change="doSortChange"
          >
            <a-radio-button
              v-for="option in SORT_OPTIONS"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </a-radio-button>
          </a-radio-group>
        </div>
      </div>
      <div v-if="tagList.length" class="tag-bar">
        <span class="filter-label">标签</span>
        <div class="tag-list">
          <a-checkable-tag
            v-for="tag in tagList"
            :key="tag"
            :checked="selectedTags.includes(tag)"
            @change="(checked: boolean) => handleTagChange(tag, checked)"
          >
            {{ tag }}
          </a-checkable-tag>
        </div>
      </div>
    </section>

    <!-- 首屏加载 -->
    <div v-if="showInitialLoading" class="state-block" data-testid="home-loading">
      <a-spin size="large" />
    </div>

    <!-- 加载失败 -->
    <div v-else-if="!dataList.length && loadError" class="state-block" data-testid="home-error">
      <a-empty description="图片加载失败，请稍后重试">
        <a-button type="primary" @click="retryLoad">重试</a-button>
      </a-empty>
    </div>

    <!-- 空结果 -->
    <div v-else-if="!dataList.length" class="state-block" data-testid="home-empty">
      <a-empty :description="emptyDescription" />
    </div>

    <!-- 规则分页画廊：等比例 4:3 封面卡片 + 分页器 -->
    <a-spin v-else :spinning="fetching">
      <div ref="listRef" class="waterfall" data-testid="waterfall">
        <PictureCard
          v-for="picture in dataList"
          :key="picture.id"
          :picture="picture"
          :thumbnail-size="thumbnailSize"
          :keyword="appliedKeyword"
          @open="doClickPicture"
        />
      </div>

      <div v-if="loadError" class="page-error" data-testid="page-error">
        <span class="load-more-error">{{ loadError }}</span>
        <a-button size="small" type="primary" ghost @click="retryLoad">重试</a-button>
      </div>

      <div class="pagination-bar" data-testid="home-pagination">
        <a-pagination
          :current="currentPage"
          :page-size="pageSize"
          :total="total"
          :page-size-options="PAGE_SIZE_OPTIONS"
          show-size-changer
          show-quick-jumper
          :show-total="(t: number) => `共 ${t} 张`"
          @change="handlePageChange"
        />
      </div>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useDevicePixelRatio, useElementSize } from '@vueuse/core'
import { listPictureTagCategory, listPictureVoByPage } from '@/api/pictureController'
import { fetchSearchHot } from '@/api/searchExtra'
import { useRagConfig } from '@/composables/useRagConfig'
import PictureCard from '@/components/PictureCard.vue'
import SearchBox from '@/components/SearchBox.vue'
import { getColumnCount, getColumnWidth, getThumbnailSize } from '@/utils/waterfall'

// 后端公开列表每页上限 20 条，支持 8 / 12 / 16 / 20 每页切换
const DEFAULT_PAGE_SIZE = 16
const PAGE_SIZE_OPTIONS = ['8', '12', '16', '20']
// 从详情页返回首页时用于恢复滚动位置
const HOME_SCROLL_KEY = 'picture:home-scroll-top'

const route = useRoute()
const router = useRouter()

const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const fetching = ref(false)
const initialLoading = ref(true)
const loadError = ref('')

// 搜索条件
const searchText = ref('')
const selectedCategory = ref<string>('all')
const selectedTags = ref<string[]>([])
const categoryList = ref<string[]>([])
const tagList = ref<string[]>([])
// 热门搜索词：搜索框与空结果引导共用同一份数据，避免两次请求
const hotTagList = ref<string[]>([])
const hotCategoryList = ref<string[]>([])
// 当前列表实际使用的搜索词（用于卡片高亮），与用户正在输入的内容区分
const appliedKeyword = ref('')

// RAG 开关：开启混合检索时，带关键词的请求显式传 searchMode=hybrid
const { ragConfig, loadRagConfig } = useRagConfig()
const searchPlaceholder = computed(() =>
  ragConfig.value.hybridSearch ? '描述你想要的图，例如：暖色调日落海报' : '从海量图片中搜索',
)

// 排序：最新（默认）/ 最多收藏 / 最多点赞 / 最多下载
const SORT_OPTIONS = [
  { label: '最新', value: 'createTime' },
  { label: '最多收藏', value: 'favoriteCount' },
  { label: '最多点赞', value: 'likeCount' },
  { label: '最多下载', value: 'downloadCount' },
] as const
const sortField = ref<string>('createTime')

const showInitialLoading = computed(() => initialLoading.value && dataList.value.length === 0)

// 空结果文案：带上当前搜索词，并指向搜索框下方的热门搜索
const emptyDescription = computed(() =>
  appliedKeyword.value
    ? `没有找到与「${appliedKeyword.value}」相关的图片，试试上方的热门搜索或更换条件`
    : '暂无图片，换个搜索条件试试',
)

// 网格列宽与缩略图尺寸
const listRef = ref<HTMLElement | null>(null)
const { width: containerWidth } = useElementSize(listRef)
const { pixelRatio } = useDevicePixelRatio()
// 页面被 keep-alive 移出 DOM 时宽度会变成 0，记住最后一次有效宽度
const lastContainerWidth = ref(0)
watch(containerWidth, (value) => {
  if (value > 0) {
    lastContainerWidth.value = value
  }
})
const effectiveWidth = computed(() => lastContainerWidth.value || 1200)
const columnCount = computed(() => getColumnCount(effectiveWidth.value))
const thumbnailSize = computed(() =>
  getThumbnailSize(getColumnWidth(effectiveWidth.value, columnCount.value), pixelRatio.value),
)

// 请求序号：切换搜索条件或翻页时丢弃过期响应
let requestSeq = 0

const buildQuery = (page: number): API.PictureQueryRequest => ({
  current: page,
  pageSize: pageSize.value,
  sortField: sortField.value,
  sortOrder: 'descend',
  searchText: searchText.value.trim() || undefined,
  // 只有 RAG + ES 都开启且确实有关键词时才走 hybrid；否则保持后端默认 keyword
  searchMode: ragConfig.value.hybridSearch && searchText.value.trim() ? 'hybrid' : undefined,
  category: selectedCategory.value === 'all' ? undefined : selectedCategory.value,
  tags: selectedTags.value.length ? [...selectedTags.value] : [],
})

const fetchPage = async (page = currentPage.value, resetState = false) => {
  const seq = (requestSeq += 1)
  fetching.value = true
  loadError.value = ''
  if (resetState) {
    dataList.value = []
    total.value = 0
    initialLoading.value = true
    // 只有在真正发起查询时才更新高亮关键词，避免输入过程中列表闪烁
    appliedKeyword.value = searchText.value.trim()
  }
  try {
    const res = await listPictureVoByPage(buildQuery(page))
    if (seq !== requestSeq) {
      return
    }
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
      currentPage.value = page
    } else {
      loadError.value = res.data.message || '加载图片失败'
    }
  } catch (error: any) {
    if (seq !== requestSeq) {
      return
    }
    loadError.value = error?.message || '网络异常，请稍后重试'
  } finally {
    if (seq === requestSeq) {
      fetching.value = false
      initialLoading.value = false
    }
  }
}

const handlePageChange = (page: number, nextPageSize: number) => {
  const sizeChanged = nextPageSize !== pageSize.value
  pageSize.value = nextPageSize
  currentPage.value = sizeChanged ? 1 : page
  syncQueryToUrl()
  window.scrollTo({ top: 0, behavior: 'smooth' })
  fetchPage(currentPage.value)
}

const retryLoad = () => {
  fetchPage(currentPage.value, dataList.value.length === 0)
}

const applyQueryToState = () => {
  const query = route.query
  searchText.value = typeof query.searchText === 'string' ? query.searchText : ''
  selectedCategory.value =
    typeof query.category === 'string' && query.category ? query.category : 'all'
  const rawTags = query.tags
  selectedTags.value = Array.isArray(rawTags)
    ? rawTags.filter((tag): tag is string => typeof tag === 'string' && tag.length > 0)
    : typeof rawTags === 'string' && rawTags
      ? [rawTags]
      : []
  // 排序也写进 URL：刷新或分享链接后能还原「最热」等视图
  const rawSort = typeof query.sort === 'string' ? query.sort : ''
  sortField.value = SORT_OPTIONS.some((option) => option.value === rawSort) ? rawSort : 'createTime'
  // 页码与每页条数也支持从 URL 还原
  const rawPage = Number(query.page)
  currentPage.value = Number.isInteger(rawPage) && rawPage > 0 ? rawPage : 1
  const rawSize = Number(query.pageSize)
  pageSize.value = PAGE_SIZE_OPTIONS.includes(String(rawSize)) ? rawSize : DEFAULT_PAGE_SIZE
}

// 搜索条件与分页同步到 URL，刷新或分享链接后能还原
const syncQueryToUrl = () => {
  const query: Record<string, string | string[]> = {}
  const text = searchText.value.trim()
  if (text) {
    query.searchText = text
  }
  if (selectedCategory.value !== 'all') {
    query.category = selectedCategory.value
  }
  if (selectedTags.value.length) {
    query.tags = [...selectedTags.value]
  }
  if (sortField.value !== 'createTime') {
    query.sort = sortField.value
  }
  if (currentPage.value > 1) {
    query.page = String(currentPage.value)
  }
  if (pageSize.value !== DEFAULT_PAGE_SIZE) {
    query.pageSize = String(pageSize.value)
  }
  router.replace({ name: 'home', query }).catch(() => {
    // 地址栏同步失败不影响列表展示
  })
}

// 搜索 / 切换分类 / 勾选标签
const doSearch = () => {
  currentPage.value = 1
  syncQueryToUrl()
  window.scrollTo({ top: 0, behavior: 'auto' })
  fetchPage(1, true)
}

const handleTagChange = (tag: string, checked: boolean) => {
  const next = new Set(selectedTags.value)
  if (checked) {
    next.add(tag)
  } else {
    next.delete(tag)
  }
  selectedTags.value = [...next]
  doSearch()
}

// 搜索框提交（回车 / 点击建议 / 点击历史或热词）：统一走 doSearch，同步 URL 并重新分页
const handleSearchBoxSearch = (keyword: string) => {
  searchText.value = keyword
  doSearch()
}

// 热门搜索词：失败时静默降级，不影响搜索主流程
const getHotKeywords = async () => {
  try {
    const res = await fetchSearchHot(10)
    if (res.data.code !== 0 || !res.data.data) {
      return
    }
    hotTagList.value = (res.data.data.tags ?? [])
      .map((item) => (item.name ?? '').trim())
      .filter((name) => name.length > 0)
    hotCategoryList.value = (res.data.data.categories ?? [])
      .map((item) => (item.name ?? '').trim())
      .filter((name) => name.length > 0)
  } catch {
    // 热词只是发现入口，网络异常时不打扰用户
  }
}

// 切换排序：回到第一页并重新请求
const doSortChange = () => {
  currentPage.value = 1
  syncQueryToUrl()
  window.scrollTo({ top: 0, behavior: 'auto' })
  fetchPage(1, true)
}

// 获取标签和分类选项
const getTagCategoryOptions = async () => {
  try {
    const res = await listPictureTagCategory()
    if (res.data.code === 0 && res.data.data) {
      categoryList.value = res.data.data.categoryList ?? []
      tagList.value = res.data.data.tagList ?? []
      // 链接里带的分类可能已被管理员删除，仍保留对应 Tab，避免筛选条件在界面上「消失」
      if (
        selectedCategory.value !== 'all' &&
        !categoryList.value.includes(selectedCategory.value)
      ) {
        categoryList.value = [selectedCategory.value, ...categoryList.value]
      }
    } else {
      message.error('加载分类标签失败，' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('加载分类标签失败，' + (error?.message || '系统错误'))
  }
}

// 跳转至图片详情，并记录当前位置
const doClickPicture = (picture: API.PictureVO) => {
  try {
    sessionStorage.setItem(HOME_SCROLL_KEY, String(window.scrollY))
  } catch {
    // 隐私模式下 sessionStorage 可能不可用，不影响跳转
  }
  router.push(`/picture/${picture.id}`)
}

onMounted(async () => {
  applyQueryToState()
  // 先拿 RAG 开关：URL 里带 searchText 时，首屏查询也要按 hybrid 发起
  await loadRagConfig()
  fetchPage(currentPage.value, true)
  getTagCategoryOptions()
  getHotKeywords()
})

onActivated(() => {
  if (!dataList.value.length) {
    // 首次进入（或没有缓存数据）时没有可恢复的位置
    try {
      sessionStorage.removeItem(HOME_SCROLL_KEY)
    } catch {
      // ignore
    }
    return
  }
  let saved = ''
  try {
    saved = sessionStorage.getItem(HOME_SCROLL_KEY) ?? ''
  } catch {
    return
  }
  if (!saved) {
    return
  }
  try {
    sessionStorage.removeItem(HOME_SCROLL_KEY)
  } catch {
    // ignore
  }
  const top = Number(saved)
  if (!Number.isFinite(top)) {
    return
  }
  nextTick(() => {
    window.scrollTo({ top, behavior: 'auto' })
  })
})
</script>

<style scoped>
.hero {
  position: relative;
  padding: 28px 0 34px;
  text-align: center;
}

.hero-eyebrow {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  margin: 0 0 18px;
  padding: 6px 14px;
  color: var(--ink-700);
  font-weight: 600;
  font-size: 11.5px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  box-shadow: var(--shadow-xs);
}

.hero-eyebrow-dot {
  width: 6px;
  height: 6px;
  background: var(--brand);
  border-radius: 50%;
}

.hero-title {
  margin: 0 0 14px;
  color: var(--ink-900);
  font-weight: 800;
  font-size: clamp(30px, 4.2vw, 48px);
  line-height: 1.14;
  letter-spacing: -0.035em;
}

.hero-accent {
  position: relative;
  display: inline-block;
  padding: 0 3px;
  color: var(--brand);
}

.hero-accent::after {
  position: absolute;
  right: 2px;
  bottom: 4px;
  left: 2px;
  z-index: -1;
  height: 10px;
  background: rgba(37, 99, 235, 0.12);
  border-radius: 999px;
  content: '';
}

.hero-sub {
  max-width: 580px;
  margin: 0 auto 28px;
  color: var(--ink-500);
  font-size: 15px;
  line-height: 1.72;
}

.search-bar {
  max-width: 720px;
  margin: 0 auto;
  text-align: left;
}

.search-mode-tip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
  justify-content: center;
  margin-top: 14px;
  color: var(--ink-500);
  font-size: 12.5px;
}

.hybrid-pill {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 3px 10px;
  color: var(--brand-600);
  font-weight: 600;
  font-size: 12px;
  background: var(--brand-soft);
  border: 1px solid rgba(37, 99, 235, 0.16);
  border-radius: 999px;
}

.hybrid-dot {
  width: 6px;
  height: 6px;
  background: var(--brand);
  border-radius: 50%;
}

.hybrid-ask-link {
  color: var(--ink-900);
  font-weight: 600;
}

.hybrid-ask-link:hover {
  color: var(--brand);
}

.filters {
  margin: 4px 0 28px;
  padding: 6px 22px 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

.filters-top {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 20px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
}

.filters-categories {
  flex: 1 1 320px;
  min-width: 0;
}

.filters-categories :deep(.ant-tabs-nav) {
  margin-bottom: 0;
}

.filters-categories :deep(.ant-tabs-nav::before) {
  display: none;
}

.category-tabs :deep(.ant-tabs-tab) {
  padding: 13px 2px;
  color: var(--ink-500);
  font-weight: 500;
  font-size: 14px;
}

.category-tabs :deep(.ant-tabs-tab:hover) {
  color: var(--ink-900);
}

.category-tabs :deep(.ant-tabs-tab-active .ant-tabs-tab-btn) {
  color: var(--ink-900);
  font-weight: 600;
}

.category-tabs :deep(.ant-tabs-ink-bar) {
  background: var(--ink-900);
}

.sort-bar {
  display: inline-flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 8px 0;
}

.tag-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  padding-top: 14px;
}

.tag-list {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 8px;
}

.filter-label {
  flex: none;
  min-width: 30px;
  color: var(--ink-400);
  font-weight: 500;
  font-size: 12px;
  letter-spacing: 0.06em;
}

.sort-bar :deep(.ant-radio-group) {
  display: inline-flex;
  flex-wrap: nowrap;
  gap: 3px;
  padding: 3px;
  background: var(--surface-subtle);
  border-radius: 999px;
}

.sort-bar :deep(.ant-radio-button-wrapper) {
  height: 28px;
  padding-inline: 11px;
  color: var(--ink-500);
  font-size: 12.5px;
  line-height: 26px;
  white-space: nowrap;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 999px;
  transition:
    color 0.18s ease,
    background-color 0.18s ease,
    box-shadow 0.18s ease;
}

.sort-bar :deep(.ant-radio-button-wrapper::before) {
  display: none;
}

.sort-bar :deep(.ant-radio-button-wrapper:hover) {
  color: var(--ink-900);
}

.sort-bar :deep(.ant-radio-button-wrapper-checked) {
  color: var(--ink-900) !important;
  font-weight: 600;
  background: var(--surface) !important;
  border-color: var(--line) !important;
  box-shadow: var(--shadow-xs);
}

.tag-bar :deep(.ant-tag-checkable) {
  margin-inline-end: 0;
  padding: 3px 12px;
  color: var(--ink-700);
  font-size: 12.5px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 999px;
  transition:
    color 0.18s ease,
    border-color 0.18s ease,
    background-color 0.18s ease;
}

.tag-bar :deep(.ant-tag-checkable:hover) {
  color: var(--ink-900);
  border-color: var(--line-strong);
  background: var(--surface);
}

.tag-bar :deep(.ant-tag-checkable-checked) {
  color: #fff;
  font-weight: 500;
  background: var(--accent-dark);
  border-color: var(--accent-dark);
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 76px 0;
}

.waterfall {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20px;
  align-items: stretch;
}

@media (max-width: 1120px) {
  .waterfall {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .waterfall {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .waterfall {
    grid-template-columns: minmax(0, 1fr);
  }
}

.page-error {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}

.load-more-error {
  color: var(--danger);
  font-size: 13px;
}

.pagination-bar {
  display: flex;
  justify-content: center;
  margin-top: 32px;
  padding: 16px 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

@media (max-width: 720px) {
  .hero {
    padding: 16px 0 24px;
  }

  .filters {
    padding: 2px 14px 14px;
  }

  .sort-bar {
    width: 100%;
    padding-top: 4px;
    padding-bottom: 10px;
    overflow-x: auto;
  }

  .tag-bar {
    align-items: flex-start;
  }

  .tag-bar .filter-label {
    padding-top: 4px;
  }

  .waterfall {
    gap: 14px;
  }
}
</style>
