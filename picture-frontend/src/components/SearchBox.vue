<template>
  <div class="search-box" data-testid="search-box">
    <div class="search-box-input">
      <a-auto-complete
        v-model:value="keyword"
        class="search-box-autocomplete"
        size="large"
        :placeholder="placeholder"
        :options="suggestionGroups"
        data-testid="search-input"
        @search="handleInput"
        @select="handleSelect"
        @keydown.enter="handleEnter"
      />
      <a-button type="primary" size="large" data-testid="search-submit" @click="submit()">
        搜索
      </a-button>
    </div>

    <!-- 搜索历史：只存本地，可一键清空 -->
    <div v-if="history.length" class="search-box-row" data-testid="search-history">
      <span class="search-box-label">历史：</span>
      <a-space :size="[0, 8]" wrap>
        <a-tag
          v-for="item in history"
          :key="item"
          class="search-box-chip"
          data-testid="search-history-item"
          @click="submit(item)"
        >
          {{ item }}
        </a-tag>
      </a-space>
      <a-button type="link" size="small" data-testid="search-history-clear" @click="clearHistory">
        清空
      </a-button>
    </div>

    <!-- 热词：来自公开图片的标签与分类热度，点击直接搜索 -->
    <div v-if="hotKeywords.length" class="search-box-row" data-testid="search-hot">
      <span class="search-box-label">热门：</span>
      <a-space :size="[0, 8]" wrap>
        <a-tag
          v-for="item in hotKeywords"
          :key="item.value"
          :color="item.kind === 'tag' ? 'blue' : undefined"
          class="search-box-chip"
          data-testid="search-hot-item"
          @click="submit(item.value)"
        >
          {{ item.value }}
        </a-tag>
      </a-space>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useDebounceFn } from '@vueuse/core'
import { fetchSearchSuggest } from '@/api/searchExtra'
import {
  clearSearchHistory,
  pushSearchHistory,
  readSearchHistory,
} from '@/utils/searchHistory'

/**
 * 搜索框：联想（名称 / 标签 / 分类）+ 本地历史 + 热词。
 *
 * - 联想只查公开图片，输入 1 个字符即可触发，空输入不请求；
 * - 历史最多 10 条、去重、可清空，只存在 localStorage；
 * - 热词由父组件传入，首页空结果引导复用同一份数据，避免重复请求；
 * - 同一关键词在 500ms 内只触发一次搜索：回车与点击建议可能同时触发 submit。
 */
const props = withDefaults(
  defineProps<{
    /** 父组件持有的搜索词（v-model） */
    modelValue?: string
    placeholder?: string
    /** 热门标签 */
    hotTags?: string[]
    /** 热门分类 */
    hotCategories?: string[]
  }>(),
  {
    modelValue: '',
    placeholder: '从海量图片中搜索',
    hotTags: () => [],
    hotCategories: () => [],
  },
)

const emit = defineEmits<{
  (event: 'update:modelValue', value: string): void
  (event: 'search', keyword: string): void
}>()

const keyword = ref(props.modelValue)

// 联想最多展示 8 条/组，避免下拉框盖住整个页面
const SUGGEST_LIMIT = 8

const suggestions = ref<API.SearchSuggestVO>({})
const history = ref<string[]>([])

// 同一关键词短时间内只 emit 一次 search：回车与 @select 会同时触发
let lastSubmittedKeyword = ''
let lastSubmittedAt = 0
// 请求序号：连续输入时丢弃过期联想响应
let suggestSeq = 0

watch(
  () => props.modelValue,
  (value) => {
    if ((value ?? '') !== keyword.value) {
      keyword.value = value ?? ''
    }
  },
)

watch(keyword, (value) => {
  emit('update:modelValue', value ?? '')
})

/**
 * 把联想结果整理成分组选项：同一关键词只出现一次（图片 > 标签 > 分类）
 */
const suggestionGroups = computed(() => {
  const seen = new Set<string>()
  const groups: { label: string; options: { value: string; label: string }[] }[] = []
  const addGroup = (label: string, values: string[]) => {
    const options: { value: string; label: string }[] = []
    for (const raw of values) {
      const value = (raw ?? '').trim()
      const key = value.toLowerCase()
      if (!value || seen.has(key)) {
        continue
      }
      seen.add(key)
      options.push({ value, label: value })
      if (options.length >= SUGGEST_LIMIT) {
        break
      }
    }
    if (options.length) {
      groups.push({ label, options })
    }
  }
  addGroup('图片', (suggestions.value.names ?? []).map((item) => item.name ?? ''))
  addGroup('标签', suggestions.value.tags ?? [])
  addGroup('分类', suggestions.value.categories ?? [])
  return groups
})

/**
 * 热词：标签在前、分类在后，去重后最多展示 12 个
 */
const hotKeywords = computed(() => {
  const seen = new Set<string>()
  const result: { value: string; kind: 'tag' | 'category' }[] = []
  const append = (values: string[] | undefined, kind: 'tag' | 'category') => {
    for (const raw of values ?? []) {
      const value = (raw ?? '').trim()
      const key = value.toLowerCase()
      if (!value || seen.has(key)) {
        continue
      }
      seen.add(key)
      result.push({ value, kind })
      if (result.length >= 12) {
        return
      }
    }
  }
  append(props.hotTags, 'tag')
  append(props.hotCategories, 'category')
  return result
})

const loadSuggestions = async (value: string) => {
  const query = (value ?? '').trim()
  const seq = (suggestSeq += 1)
  if (!query) {
    suggestions.value = {}
    return
  }
  try {
    const res = await fetchSearchSuggest(query, SUGGEST_LIMIT)
    if (seq !== suggestSeq) {
      return
    }
    suggestions.value = res.data.code === 0 ? (res.data.data ?? {}) : {}
  } catch {
    // 联想失败不打扰用户：输入框仍然可以正常搜索
    if (seq === suggestSeq) {
      suggestions.value = {}
    }
  }
}

const debouncedLoad = useDebounceFn(loadSuggestions, 250)

const handleInput = (value: string) => {
  debouncedLoad(value)
}

// AutoComplete 的 value 类型是 RawValueType | LabelInValueType，这里只接受字符串/数字
const handleSelect = (value: unknown) => {
  if (typeof value === 'string' || typeof value === 'number') {
    submit(String(value))
  }
}

const handleEnter = () => {
  submit()
}

/**
 * 提交搜索：写入历史并通知父组件；空白关键词表示清空搜索条件
 */
const submit = (raw?: string) => {
  const value = (raw ?? keyword.value ?? '').trim()
  keyword.value = value
  const now = Date.now()
  if (value === lastSubmittedKeyword && now - lastSubmittedAt < 500) {
    return
  }
  lastSubmittedKeyword = value
  lastSubmittedAt = now
  if (value) {
    history.value = pushSearchHistory(value)
  }
  emit('search', value)
}

const clearHistory = () => {
  clearSearchHistory()
  history.value = []
}

onMounted(() => {
  history.value = readSearchHistory()
})
</script>

<style scoped>
.search-box {
  max-width: 680px;
  margin: 0 auto;
  text-align: center;
}

.search-box-input {
  display: flex;
  gap: 8px;
  align-items: center;
}

.search-box-autocomplete {
  flex: 1 1 auto;
  min-width: 0;
}

.search-box-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  justify-content: center;
  margin-top: 12px;
  font-size: 13px;
}

.search-box-label {
  color: rgba(0, 0, 0, 0.45);
}

.search-box-chip {
  cursor: pointer;
  user-select: none;
}
</style>
