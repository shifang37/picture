<template>
  <div id="homePage">
    <!-- 搜索框 -->
    <div class="search-bar">
      <a-input-search
        v-model:value="searchText"
        placeholder="从海量图片中搜索"
        enter-button="搜索"
        size="large"
        @search="doSearch"
      />
    </div>

    <!-- 分类 + 标签 + 排序 -->
    <a-tabs v-model:activeKey="selectedCategory" @change="doSearch">
      <a-tab-pane key="all" tab="全部" />
      <a-tab-pane v-for="category in categoryList" :key="category" :tab="category" />
    </a-tabs>
    <div class="sort-bar">
      <span class="sort-bar-label">排序：</span>
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
    <div class="tag-bar">
      <span class="tag-bar-label">标签：</span>
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
      <a-empty description="暂无图片，换个搜索条件试试" />
    </div>

    <!-- 瀑布流：按图片宽高比分配列，图片加载前即占好高度，避免布局跳动 -->
    <div v-show="dataList.length" ref="listRef" class="waterfall" data-testid="waterfall">
      <div v-for="(column, columnIndex) in columns" :key="columnIndex" class="waterfall-column">
        <PictureCard
          v-for="picture in column"
          :key="picture.id"
          :picture="picture"
          :thumbnail-size="thumbnailSize"
          @open="doClickPicture"
        />
      </div>
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
import PictureCard from '@/components/PictureCard.vue'
import {
  distributeToColumns,
  getColumnCount,
  getColumnWidth,
  getPictureAspectRatio,
  getThumbnailSize,
} from '@/utils/waterfall'

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

// 排序：最新（默认）/ 最多收藏 / 最多点赞 / 最多下载
const SORT_OPTIONS = [
  { label: '最新', value: 'createTime' },
  { label: '最多收藏', value: 'favoriteCount' },
  { label: '最多点赞', value: 'likeCount' },
  { label: '最多下载', value: 'downloadCount' },
] as const
const sortField = ref<string>('createTime')

const showInitialLoading = computed(() => initialLoading.value && dataList.value.length === 0)

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
const columns = computed(() =>
  distributeToColumns(dataList.value, columnCount.value, getPictureAspectRatio),
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
.search-bar {
  margin-bottom: 24px;
  text-align: center;
}

#homePage .search-bar {
  max-width: 480px;
  margin: 0 auto 16px;
}

.tag-bar {
  margin-bottom: 16px;
}

.tag-bar-label {
  margin-right: 8px;
}

.sort-bar {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
}

.sort-bar-label {
  margin-right: 8px;
  color: #666;
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.waterfall {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.waterfall-column {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 48px;
  padding: 16px 0;
  color: rgba(0, 0, 0, 0.45);
}

.load-more-error {
  color: #ff4d4f;
}
</style>
