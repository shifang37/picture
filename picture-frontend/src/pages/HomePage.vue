<template>
  <div id="homePage">
    <!-- 首屏：一句主张 + 搜索入口，搜索框沿用原有联想 / 历史 / 热词能力 -->
    <section class="hero">
      <p class="hero-eyebrow">SHIFANG37 · 图片素材库</p>
      <h1 class="hero-title">收藏每一个<span class="hero-accent">值得被看见</span>的画面</h1>
      <p class="hero-sub">上传、整理、检索与分享你的图片，标签和分类让素材随时可用</p>
      <div class="search-bar">
        <SearchBox
          v-model="searchText"
          :hot-tags="hotTagList"
          :hot-categories="hotCategoryList"
          @search="handleSearchBoxSearch"
        />
      </div>
    </section>

    <!-- 分类 / 排序 / 标签：统一收进一张筛选面板 -->
    <section class="filters">
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
          <a-radio-button v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </a-radio-button>
        </a-radio-group>
      </div>
      <div v-if="tagList.length" class="tag-bar">
        <span class="filter-label">标签</span>
        <a-space :size="[0, 8]" wrap>
          <a-checkable-tag
            v-for="tag in tagList"
            :key="tag"
            :checked="selectedTags.includes(tag)"
            @change="(checked: boolean) => handleTagChange(tag, checked)"
          >
            {{ tag }}
          </a-checkable-tag>
        </a-space>
      </div>
    </section>

    <!-- 首屏加载 -->
    <div v-if="showInitialLoading" class="state-block" data-testid="home-loading">
      <a-spin size="large" />
    </div>

    <!-- 首屏失败 -->
    <div v-else-if="!dataList.length && loadError" class="state-block" data-testid="home-error">
      <a-empty description="图片加载失败，请稍后重试">
        <a-button type="primary" @click="retryLoad">重试</a-button>
      </a-empty>
    </div>

    <!-- 空结果 -->
    <div v-else-if="!dataList.length" class="state-block" data-testid="home-empty">
      <a-empty :description="emptyDescription" />
    </div>

    <!-- 等比例网格：所有封面统一 4:3，一行高度齐平，避免瀑布流的错落感 -->
    <div v-show="dataList.length" ref="listRef" class="waterfall" data-testid="waterfall">
      <PictureCard
        v-for="picture in dataList"
        :key="picture.id"
        :picture="picture"
        :thumbnail-size="thumbnailSize"
        :keyword="appliedKeyword"
        @open="doClickPicture"
      />
    </div>

    <!-- 加载更多 / 没有更多 -->
    <div v-if="dataList.length" class="load-more" data-testid="load-more">
      <template v-if="fetching">
        <a-spin size="small" />
        <span>正在加载更多...</span>
      </template>
      <template v-else-if="loadError">
        <span class="load-more-error">{{ loadError }}</span>
        <a-button size="small" type="primary" ghost @click="retryLoad">重试</a-button>
      </template>
      <span v-else-if="!hasMore" class="load-more-end">没有更多了，共 {{ total }} 张</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useDevicePixelRatio, useElementSize, useInfiniteScroll } from '@vueuse/core'
import { listPictureTagCategory, listPictureVoByPage } from '@/api/pictureController'
import { fetchSearchHot } from '@/api/searchExtra'
import PictureCard from '@/components/PictureCard.vue'
import SearchBox from '@/components/SearchBox.vue'
import { getColumnCount, getColumnWidth, getThumbnailSize } from '@/utils/waterfall'

// 后端公开列表每页上限 20 条
const PAGE_SIZE = 20
// 从详情页返回首页时用于恢复滚动位置
const HOME_SCROLL_KEY = 'picture:home-scroll-top'

const route = useRoute()
const router = useRouter()

const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const currentPage = ref(0)
const hasMore = ref(false)
const fetching = ref(false)
const initialLoading = ref(true)
const loadError = ref('')
// keep-alive 缓存期间暂停滚动加载，避免在详情页误触发首页分页
const isActive = ref(true)

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

// 瀑布流列数与缩略图尺寸
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

// 请求序号：切换搜索条件时丢弃过期响应
let requestSeq = 0

const buildQuery = (page: number): API.PictureQueryRequest => ({
  current: page,
  pageSize: PAGE_SIZE,
  sortField: sortField.value,
  sortOrder: 'descend',
  searchText: searchText.value.trim() || undefined,
  category: selectedCategory.value === 'all' ? undefined : selectedCategory.value,
  tags: selectedTags.value.length ? [...selectedTags.value] : [],
})

const fetchPage = async (reset = false) => {
  if (!reset && (fetching.value || !hasMore.value)) {
    return
  }
  const seq = (requestSeq += 1)
  fetching.value = true
  loadError.value = ''
  if (reset) {
    dataList.value = []
    total.value = 0
    currentPage.value = 0
    hasMore.value = false
    initialLoading.value = true
    // 只有在真正发起查询时才更新高亮关键词，避免输入过程中列表闪烁
    appliedKeyword.value = searchText.value.trim()
  }
  const page = currentPage.value + 1
  try {
    const res = await listPictureVoByPage(buildQuery(page))
    if (seq !== requestSeq) {
      return
    }
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records ?? []
      const newTotal = Number(res.data.data.total) || 0
      // 滚动期间若有新图片过审，后续页会整体后移产生重复，按 id 去重
      const seenIds = new Set(dataList.value.map((picture) => String(picture.id)))
      const uniqueRecords = records.filter((picture) => {
        const id = String(picture.id)
        if (!picture.id || seenIds.has(id)) {
          return false
        }
        seenIds.add(id)
        return true
      })
      dataList.value = [...dataList.value, ...uniqueRecords]
      total.value = newTotal
      currentPage.value = page
      // 先让新卡片渲染完成，再开启下一次滚动加载；否则列表还是旧高度时会被误判为「已到底部」
      hasMore.value = false
      if (records.length >= PAGE_SIZE && page * PAGE_SIZE < newTotal) {
        nextTick(() => {
          hasMore.value = true
        })
      }
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

// 滚动到距底部 400px 时自动加载下一页；加载失败后等待用户点重试，避免请求风暴
useInfiniteScroll(typeof window === 'undefined' ? null : window, () => fetchPage(), {
  distance: 400,
  interval: 200,
  canLoadMore: () => isActive.value && hasMore.value && !fetching.value && !loadError.value,
})

const retryLoad = () => {
  if (currentPage.value === 0) {
    fetchPage(true)
  } else {
    loadError.value = ''
    fetchPage()
  }
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
}

// 搜索条件同步到 URL，刷新或分享链接后能还原
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
  router.replace({ name: 'home', query }).catch(() => {
    // 地址栏同步失败不影响列表展示
  })
}

// 搜索 / 切换分类 / 勾选标签
const doSearch = () => {
  syncQueryToUrl()
  window.scrollTo({ top: 0, behavior: 'auto' })
  fetchPage(true)
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

// 切换排序：回到顶部并重新分页，避免停留在旧列表的滚动位置
const doSortChange = () => {
  syncQueryToUrl()
  window.scrollTo({ top: 0, behavior: 'auto' })
  fetchPage(true)
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

onMounted(() => {
  applyQueryToState()
  fetchPage(true)
  getTagCategoryOptions()
  getHotKeywords()
})

onActivated(() => {
  isActive.value = true
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

onDeactivated(() => {
  isActive.value = false
})
</script>

<style scoped>
.hero {
  padding: 36px 0 30px;
  text-align: center;
}

.hero-eyebrow {
  display: inline-flex;
  align-items: center;
  margin: 0 0 18px;
  padding: 6px 14px;
  color: var(--brand-600);
  font-weight: 600;
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--line);
  border-radius: 999px;
  box-shadow: var(--shadow-sm);
}

.hero-title {
  margin: 0 0 14px;
  color: var(--ink-900);
  font-weight: 700;
  font-size: clamp(30px, 4vw, 46px);
  line-height: 1.16;
  letter-spacing: -0.03em;
}

.hero-accent {
  padding: 0 2px;
  background: linear-gradient(120deg, #4f46e5 0%, #9333ea 55%, #ec4899 100%);
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero-sub {
  max-width: 560px;
  margin: 0 auto 28px;
  color: var(--ink-500);
  font-size: 15px;
  line-height: 1.7;
}

.search-bar {
  max-width: 720px;
  margin: 0 auto;
  text-align: left;
}

.filters {
  margin: 4px 0 28px;
  padding: 2px 22px 16px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  backdrop-filter: blur(8px);
}

.filters-categories :deep(.ant-tabs-nav) {
  margin-bottom: 8px;
}

.filters-categories :deep(.ant-tabs-nav::before) {
  border-bottom: 1px solid var(--line);
}

.category-tabs :deep(.ant-tabs-tab) {
  padding: 14px 2px;
  font-size: 14px;
  font-weight: 500;
}

.category-tabs :deep(.ant-tabs-tab-active) {
  font-weight: 600;
}

.sort-bar,
.tag-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  padding: 8px 0;
}

.filter-label {
  flex: none;
  min-width: 32px;
  color: var(--ink-400);
  font-size: 12px;
  letter-spacing: 0.08em;
}

.sort-bar :deep(.ant-radio-group) {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  background: transparent;
}

.sort-bar :deep(.ant-radio-button-wrapper) {
  height: 32px;
  padding-inline: 14px;
  color: var(--ink-500);
  line-height: 30px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 999px;
  transition:
    color 0.18s ease,
    border-color 0.18s ease,
    background 0.18s ease;
}

.sort-bar :deep(.ant-radio-button-wrapper::before) {
  display: none;
}

.sort-bar :deep(.ant-radio-button-wrapper:hover) {
  color: var(--brand-600);
  border-color: var(--brand-ring);
}

.sort-bar :deep(.ant-radio-button-wrapper-checked) {
  color: #fff;
  background: linear-gradient(135deg, var(--brand) 0%, var(--brand-2) 100%);
  border-color: transparent;
  box-shadow: 0 8px 18px -10px rgba(79, 70, 229, 0.9);
}

.tag-bar :deep(.ant-tag-checkable) {
  padding: 3px 12px;
  color: var(--ink-500);
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 999px;
  transition:
    color 0.18s ease,
    border-color 0.18s ease,
    background 0.18s ease;
}

.tag-bar :deep(.ant-tag-checkable:hover) {
  color: var(--brand-600);
  border-color: var(--brand-ring);
}

.tag-bar :deep(.ant-tag-checkable-checked) {
  color: var(--brand-600);
  font-weight: 600;
  background: var(--brand-soft);
  border-color: var(--brand-ring);
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 72px 0;
}

.waterfall {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  align-items: stretch;
}

@media (max-width: 1100px) {
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

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 48px;
  margin-top: 8px;
  padding: 24px 0;
  color: var(--ink-400);
}

.load-more-error {
  color: #e11d48;
}

.load-more-end {
  font-size: 12.5px;
  letter-spacing: 0.06em;
}

@media (max-width: 720px) {
  .hero {
    padding: 20px 0 24px;
  }

  .filters {
    padding: 0 14px 12px;
  }

  .waterfall {
    gap: 12px;
  }
}
</style>
