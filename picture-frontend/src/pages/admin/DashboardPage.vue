<template>
  <div id="dashboardPage">
    <div class="gallery-page-head">
      <div>
        <h2 class="gallery-page-title">数据看板</h2>
        <div class="gallery-page-desc">
          统计结果在后端缓存 5 分钟；点击刷新会重新请求，但短时间内仍可能命中缓存。
        </div>
      </div>
      <a-button :loading="loading" @click="fetchAll">
        <template #icon>
          <ReloadOutlined />
        </template>
        刷新
      </a-button>
    </div>

    <!-- 运维操作：计数漂移修复与搜索索引维护。三个按钮都走二次确认，避免误触全量重算 -->
    <a-card size="small" title="运维操作" class="ops-card">
      <div class="ops-tip">
        用于修复计数漂移、重建搜索索引；执行会直接写库或重建索引，请确认当前没有正在进行的批量操作。
      </div>
      <a-space wrap>
        <a-popconfirm
          title="重建搜索索引？"
          description="按 MySQL 全量重建公开图片索引，未启用 Elasticsearch 时会直接失败"
          ok-text="重建索引"
          cancel-text="取消"
          @confirm="handleReindex"
        >
          <a-button :loading="reindexing">重建搜索索引</a-button>
        </a-popconfirm>
        <a-popconfirm
          title="重算全部互动计数？"
          description="按关系表重算所有图片的收藏数与点赞数，不会改动用户数据"
          ok-text="重算互动"
          cancel-text="取消"
          @confirm="handleRecountInteraction"
        >
          <a-button :loading="recountingInteraction">重算互动计数</a-button>
        </a-popconfirm>
        <a-popconfirm
          title="重算全部专辑计数？"
          description="按关系表重算所有专辑的图片数量，不会改动图片本身"
          ok-text="重算专辑"
          cancel-text="取消"
          @confirm="handleRecountAlbum"
        >
          <a-button :loading="recountingAlbum">重算专辑计数</a-button>
        </a-popconfirm>
      </a-space>
    </a-card>

    <!-- RAG 状态：索引数量、向量化待处理/失败、模型版本与别名健康度；关闭时明确提示 -->
    <a-card size="small" title="RAG 检索与问答" class="rag-card" data-testid="rag-status-card">
      <template #extra>
        <a-tag :color="ragConfig.enabled ? 'green' : 'default'">
          {{ ragConfig.enabled ? '已开启' : '未开启' }}
        </a-tag>
      </template>
      <a-spin :spinning="ragLoading">
        <template v-if="ragConfig.enabled">
          <a-alert
            v-if="ragStatusError"
            type="warning"
            show-icon
            :message="ragStatusError"
            data-testid="rag-status-error"
          />
          <template v-else-if="ragStatus">
            <a-descriptions :column="{ xs: 1, sm: 2, md: 3 }" size="small" bordered>
              <a-descriptions-item label="索引文档">
                {{ formatCount(ragStatus.docCount) }}
              </a-descriptions-item>
              <a-descriptions-item label="待处理">
                {{ formatCount(ragStatus.pendingCount) }}
              </a-descriptions-item>
              <a-descriptions-item label="失败">
                {{ formatCount(ragStatus.failedCount) }}
              </a-descriptions-item>
              <a-descriptions-item label="文本模型">
                {{ ragStatus.textModel || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="图片模型">
                {{ ragStatus.imageModel || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="索引 / 别名">
                {{ ragStatus.indexName || '-' }} / {{ ragStatus.alias || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="维度">
                {{ ragStatus.dims ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="模型 / 语料版本">
                {{ ragStatus.version || '-' }} / {{ ragStatus.textVersion || '-' }}
              </a-descriptions-item>
            </a-descriptions>
            <a-alert
              v-if="ragStatus.stale"
              class="rag-stale"
              type="error"
              show-icon
              message="向量索引别名不兼容，增量写入已被守卫拒绝"
              description="请点击下方「重建向量索引」，重建成功后会原子切换别名。"
              data-testid="rag-status-stale"
            />
            <div class="rag-actions">
              <a-popconfirm
                title="重建向量索引？"
                description="按公开图片全量重新向量化并原子切换别名，图片较多时会持续一段时间"
                ok-text="重建"
                cancel-text="取消"
                @confirm="handleRagReindex"
              >
                <a-button :loading="reindexingRag" data-testid="rag-reindex">重建向量索引</a-button>
              </a-popconfirm>
              <a-button :loading="ragLoading" @click="fetchRagStatus">刷新状态</a-button>
            </div>
          </template>
          <a-empty v-else description="暂无 RAG 状态数据" />
        </template>
        <a-empty v-else description="RAG 未启用（RAG_ENABLED=false），当前搜索只走 BM25 / MySQL" />
      </a-spin>
    </a-card>

    <a-alert v-if="errorMessage" type="error" show-icon :message="errorMessage" class="error-alert">
      <template #action>
        <a-button size="small" @click="fetchAll">重试</a-button>
      </template>
    </a-alert>

    <a-spin :spinning="loading">
      <!-- 核心指标 -->
      <div class="metric-grid">
        <a-card
          v-for="metric in overviewCards"
          :key="metric.label"
          size="small"
          class="metric-card"
        >
          <div class="metric-label">{{ metric.label }}</div>
          <div class="metric-value">{{ metric.value }}</div>
          <div class="metric-hint">{{ metric.hint }}</div>
        </a-card>
      </div>

      <!-- 趋势 + 审核 -->
      <a-row :gutter="[16, 16]" class="chart-row">
        <a-col :xs="24" :lg="16">
          <a-card title="近 30 天上传趋势" size="small">
            <div v-if="trend.length" class="chart">
              <VChart :option="trendOption" autoresize />
            </div>
            <a-empty v-else description="暂无上传数据" />
          </a-card>
        </a-col>
        <a-col :xs="24" :lg="8">
          <a-card title="审核结果" size="small">
            <template v-if="hasReviewData">
              <div class="chart chart-small">
                <VChart :option="reviewOption" autoresize />
              </div>
              <div class="review-summary">
                已通过 {{ formatCount(review?.approvedCount) }} 张 · 已拒绝
                {{ formatCount(review?.rejectedCount) }} 张
              </div>
              <div class="review-average">
                平均审核耗时：{{ formatDuration(review?.averageReviewSeconds) }}
              </div>
            </template>
            <a-empty v-else description="暂无审核记录" />
          </a-card>
        </a-col>
      </a-row>

      <!-- 分类 + 标签 -->
      <a-row :gutter="[16, 16]">
        <a-col :xs="24" :lg="12">
          <a-card title="分类分布（图片数）" size="small">
            <div v-if="categories.length" class="chart">
              <VChart :option="categoryOption" autoresize />
            </div>
            <a-empty v-else description="暂无图片分类数据" />
          </a-card>
        </a-col>
        <a-col :xs="24" :lg="12">
          <a-card title="标签使用排行" size="small">
            <div v-if="tags.length" class="chart chart-tall">
              <VChart :option="tagOption" autoresize />
            </div>
            <a-empty v-else description="暂无图片标签数据" />
          </a-card>
        </a-col>
      </a-row>

      <!-- 下载排行 -->
      <a-card title="下载次数排行" size="small" class="table-card">
        <a-table
          :columns="topColumns"
          :data-source="topDownload"
          :pagination="false"
          row-key="id"
          size="small"
          :scroll="{ x: 640 }"
        >
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'rank'">
              <span class="rank">{{ index + 1 }}</span>
            </template>
            <template v-else-if="column.key === 'name'">
              <a class="picture-link" @click="goDetail(record.id)">
                {{ record.name || '未命名' }}
              </a>
            </template>
            <template v-else-if="column.key === 'downloadCount'">
              {{ formatCount(record.downloadCount) }}
            </template>
          </template>
        </a-table>
        <a-empty v-if="!loading && !topDownload.length" description="暂无下载记录" />
      </a-card>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ReloadOutlined } from '@ant-design/icons-vue'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import type { EChartsOption } from 'echarts'
import {
  getAnalyzeCategory,
  getAnalyzeOverview,
  getAnalyzeReview,
  getAnalyzeTags,
  getAnalyzeTopDownload,
  getAnalyzeUploadTrend,
  type AnalyzeCategoryItem,
  type AnalyzeOverview,
  type AnalyzeReview,
  type AnalyzeTagItem,
  type AnalyzeTopDownloadItem,
  type AnalyzeUploadTrendItem,
} from '@/api/analyzeExtra'
import { recountAlbums, recountInteractions, reindexSearch } from '@/api/adminOpsExtra'
import { fetchRagStatus as fetchRagStatusApi, reindexRag, type RagStatus } from '@/api/ragExtra'
import { useRagConfig } from '@/composables/useRagConfig'
import { formatSize } from '@/utils'
import { formatCount, formatDuration, toNumber } from '@/utils/analyze'

// 按需注册 ECharts：这些代码只随看板页面分包加载，不影响首页
use([
  CanvasRenderer,
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
])

const router = useRouter()

const loading = ref(false)
const errorMessage = ref('')
const overview = ref<AnalyzeOverview>()
const trend = ref<AnalyzeUploadTrendItem[]>([])
const categories = ref<AnalyzeCategoryItem[]>([])
const tags = ref<AnalyzeTagItem[]>([])
const review = ref<AnalyzeReview>()
const topDownload = ref<AnalyzeTopDownloadItem[]>([])

// RAG 状态卡：开关来自公开配置，索引数据来自管理员接口（仅开启时请求）
const { ragConfig, loadRagConfig } = useRagConfig()
const ragStatus = ref<RagStatus>()
const ragStatusError = ref('')
const ragLoading = ref(false)
const reindexingRag = ref(false)

const overviewCards = computed(() => [
  {
    label: '用户总数',
    value: formatCount(overview.value?.userCount),
    hint: '未删除的注册用户',
  },
  {
    label: '图片总数',
    value: formatCount(overview.value?.pictureCount),
    hint: '未删除的图片',
  },
  {
    label: '待审核',
    value: formatCount(overview.value?.pendingReviewCount),
    hint: '等待管理员处理',
  },
  {
    label: '总存储体积',
    value: formatSize(overview.value?.totalPicSize),
    hint: '所有图片体积之和',
  },
  {
    label: '总下载次数',
    value: formatCount(overview.value?.totalDownloadCount),
    hint: '按用户/IP 每日去重',
  },
])

const hasReviewData = computed(
  () => toNumber(review.value?.approvedCount) + toNumber(review.value?.rejectedCount) > 0,
)

// ==================== 图表配置 ====================

const trendOption = computed<EChartsOption>(() => ({
  color: ['#2563eb'],
  tooltip: { trigger: 'axis' },
  grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((item) => item.date ?? ''),
    axisLine: { lineStyle: { color: '#e4e4e7' } },
    axisTick: { show: false },
    axisLabel: {
      color: '#71717a',
      formatter: (value: string) => value.slice(5),
    },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#f4f4f5' } },
    axisLabel: { color: '#71717a' },
  },
  series: [
    {
      name: '上传数量',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trend.value.map((item) => toNumber(item.count)),
      lineStyle: { width: 2.5 },
      areaStyle: { opacity: 0.14 },
    },
  ],
}))

const reviewOption = computed<EChartsOption>(() => ({
  color: ['#10b981', '#e11d48'],
  tooltip: { trigger: 'item', formatter: '{b}：{c} 张（{d}%）' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 10, itemHeight: 10 },
  series: [
    {
      type: 'pie',
      radius: ['54%', '74%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 3 },
      label: { show: false },
      data: [
        { name: '已通过', value: toNumber(review.value?.approvedCount) },
        { name: '已拒绝', value: toNumber(review.value?.rejectedCount) },
      ],
    },
  ],
}))

const categoryOption = computed<EChartsOption>(() => ({
  color: ['#18181b'],
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (params: any) => {
      const item = categories.value[params?.[0]?.dataIndex ?? 0]
      if (!item) {
        return ''
      }
      return `${item.category ?? '未分类'}<br/>图片数：${formatCount(item.count)}<br/>体积：${formatSize(item.totalSize)}`
    },
  },
  grid: {
    left: 8,
    right: 16,
    top: 16,
    bottom: categories.value.length > 6 ? 56 : 24,
    containLabel: true,
  },
  xAxis: {
    type: 'category',
    data: categories.value.map((item) => item.category ?? '未分类'),
    axisTick: { show: false },
    axisLine: { lineStyle: { color: '#e4e4e7' } },
    axisLabel: {
      color: '#71717a',
      interval: 0,
      rotate: categories.value.length > 6 ? 30 : 0,
    },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#f4f4f5' } },
    axisLabel: { color: '#71717a' },
  },
  series: [
    {
      name: '图片数',
      type: 'bar',
      barMaxWidth: 30,
      itemStyle: { borderRadius: [6, 6, 0, 0] },
      data: categories.value.map((item) => toNumber(item.count)),
    },
  ],
}))

const tagOption = computed<EChartsOption>(() => {
  // 横向条形图从下往上画，反转后使用最多的标签显示在最上方
  const items = [...tags.value].reverse()
  return {
    color: ['#2563eb'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 36, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f4f4f5' } },
      axisLabel: { color: '#71717a' },
    },
    yAxis: {
      type: 'category',
      data: items.map((item) => item.tag ?? ''),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: '#3f3f46' },
    },
    series: [
      {
        name: '使用次数',
        type: 'bar',
        barMaxWidth: 14,
        itemStyle: { borderRadius: [0, 6, 6, 0] },
        label: { show: true, position: 'right', color: '#71717a' },
        data: items.map((item) => toNumber(item.count)),
      },
    ],
  }
})

const topColumns = [
  { title: '排名', key: 'rank', width: 70 },
  { title: '图片', key: 'name', ellipsis: true },
  { title: '下载次数', key: 'downloadCount', width: 120, align: 'right' as const },
]

// ==================== 数据加载 ====================

const fetchAll = async () => {
  loading.value = true
  errorMessage.value = ''
  try {
    const [overviewRes, trendRes, categoryRes, tagRes, reviewRes, topRes] = await Promise.all([
      getAnalyzeOverview(),
      getAnalyzeUploadTrend(30),
      getAnalyzeCategory(),
      getAnalyzeTags(20),
      getAnalyzeReview(),
      getAnalyzeTopDownload(10),
    ])
    const failed = [overviewRes, trendRes, categoryRes, tagRes, reviewRes, topRes].find(
      (res) => res.data.code !== 0,
    )
    if (failed) {
      throw new Error(failed.data.message || '加载看板数据失败')
    }
    overview.value = overviewRes.data.data ?? {}
    trend.value = trendRes.data.data ?? []
    categories.value = categoryRes.data.data ?? []
    tags.value = tagRes.data.data ?? []
    review.value = reviewRes.data.data ?? {}
    topDownload.value = topRes.data.data ?? []
  } catch (error: any) {
    errorMessage.value = '加载看板数据失败：' + (error?.message || '系统错误')
    message.error(errorMessage.value)
  } finally {
    loading.value = false
  }
}

const goDetail = (id?: string) => {
  if (id) {
    router.push(`/picture/${id}`)
  }
}

// ==================== 运维操作 ====================
// 三个操作都可能耗时（全量分批），按钮各自 loading，避免重复触发
const reindexing = ref(false)
const recountingInteraction = ref(false)
const recountingAlbum = ref(false)

/** 统一处理「业务码 + 异常」两种失败，成功时返回处理条数 */
const unwrapOpsResult = (res: { data: { code?: number; data?: number; message?: string } }) => {
  if (res.data.code !== 0) {
    throw new Error(res.data.message || '操作失败')
  }
  return Number(res.data.data ?? 0)
}

const handleReindex = async () => {
  reindexing.value = true
  try {
    const indexed = unwrapOpsResult(await reindexSearch())
    message.success(`索引已重建，共写入 ${indexed} 张图片`)
  } catch (error: any) {
    message.error('重建索引失败：' + (error?.message || '系统错误'))
  } finally {
    reindexing.value = false
  }
}

const handleRecountInteraction = async () => {
  recountingInteraction.value = true
  try {
    const updated = unwrapOpsResult(await recountInteractions())
    message.success(`互动计数已重算，共更新 ${updated} 张图片`)
  } catch (error: any) {
    message.error('重算互动计数失败：' + (error?.message || '系统错误'))
  } finally {
    recountingInteraction.value = false
  }
}

const handleRecountAlbum = async () => {
  recountingAlbum.value = true
  try {
    const updated = unwrapOpsResult(await recountAlbums())
    message.success(`专辑计数已重算，共更新 ${updated} 个专辑`)
  } catch (error: any) {
    message.error('重算专辑计数失败：' + (error?.message || '系统错误'))
  } finally {
    recountingAlbum.value = false
  }
}

// ==================== RAG 状态 ====================

/** 读取向量索引状态；RAG 关闭或搜索非 ES 时后端会返回明确的业务错误 */
const fetchRagStatus = async () => {
  if (!ragConfig.value.enabled) {
    return
  }
  ragLoading.value = true
  ragStatusError.value = ''
  try {
    const res = await fetchRagStatusApi()
    if (res.data.code === 0 && res.data.data) {
      ragStatus.value = res.data.data
    } else {
      ragStatusError.value = res.data.message || '读取 RAG 状态失败'
    }
  } catch (error: any) {
    ragStatusError.value = '读取 RAG 状态失败：' + (error?.message || '系统错误')
  } finally {
    ragLoading.value = false
  }
}

const handleRagReindex = async () => {
  reindexingRag.value = true
  try {
    const res = await reindexRag()
    if (res.data.code !== 0) {
      throw new Error(res.data.message || '重建向量索引失败')
    }
    const data = res.data.data ?? {}
    if (data.switched) {
      message.success(
        `向量索引已重建并切换：写入 ${data.indexed ?? 0} 张，跳过 ${data.skipped ?? 0} 张`,
      )
    } else {
      message.warning(
        `重建未切换别名：写入 ${data.indexed ?? 0} 张，失败 ${data.failed ?? 0} 张，旧索引继续可用`,
      )
    }
    await fetchRagStatus()
  } catch (error: any) {
    message.error('重建向量索引失败：' + (error?.message || '系统错误'))
  } finally {
    reindexingRag.value = false
  }
}

onMounted(async () => {
  fetchAll()
  // 先确认 RAG 总开关，再决定是否请求管理员状态接口（关闭时该接口会返回业务错误）
  await loadRagConfig()
  fetchRagStatus()
})
</script>

<style scoped>
#dashboardPage {
  max-width: 1240px;
  margin: 0 auto;
}

.error-alert {
  margin-bottom: 18px;
}

.ops-card {
  margin-bottom: 18px;
}

.ops-tip {
  margin-bottom: 12px;
  color: var(--ink-500);
  font-size: 13px;
}

.rag-card {
  margin-bottom: 18px;
}

.rag-stale {
  margin-top: 12px;
}

.rag-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 14px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 16px;
  margin-bottom: 18px;
}

.metric-card {
  height: 100%;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.metric-card:hover {
  box-shadow: var(--shadow-sm);
  transform: translateY(-2px);
}

.metric-label {
  color: var(--ink-500);
  font-weight: 500;
  font-size: 13px;
}

.metric-value {
  margin-top: 8px;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 28px;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
  letter-spacing: -0.02em;
  word-break: break-all;
}

.metric-hint {
  margin-top: 6px;
  color: var(--ink-400);
  font-size: 12px;
}

.chart-row {
  margin-bottom: 18px;
}

.chart {
  width: 100%;
  height: 320px;
}

.chart-small {
  height: 220px;
}

.chart-tall {
  height: 420px;
}

.review-summary {
  color: var(--ink-700);
  font-weight: 500;
  font-size: 13px;
  text-align: center;
}

.review-average {
  margin-top: 4px;
  color: var(--ink-400);
  font-size: 12.5px;
  text-align: center;
}

.table-card {
  margin-top: 18px;
}

.rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  color: var(--ink-500);
  font-weight: 600;
  font-size: 12px;
  background: var(--surface-subtle);
  border-radius: 999px;
}

.picture-link {
  color: var(--ink-900);
  font-weight: 500;
}

.picture-link:hover {
  color: var(--brand);
}
</style>
