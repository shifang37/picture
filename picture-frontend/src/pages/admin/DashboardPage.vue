<template>
  <div id="dashboardPage">
    <a-flex justify="space-between" align="center" wrap="wrap" gap="12" class="page-header">
      <div>
        <h2 class="page-title">数据看板</h2>
        <div class="page-tip">
          统计结果在后端缓存 5 分钟；点击刷新会重新请求，但短时间内仍可能命中缓存。
        </div>
      </div>
      <a-button :loading="loading" @click="fetchAll">
        <template #icon>
          <ReloadOutlined />
        </template>
        刷新
      </a-button>
    </a-flex>

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
  color: ['#1677ff'],
  tooltip: { trigger: 'axis' },
  grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((item) => item.date ?? ''),
    axisLine: { lineStyle: { color: '#e5e7eb' } },
    axisTick: { show: false },
    axisLabel: {
      color: '#6b7280',
      formatter: (value: string) => value.slice(5),
    },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#f0f0f0' } },
    axisLabel: { color: '#6b7280' },
  },
  series: [
    {
      name: '上传数量',
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: trend.value.map((item) => toNumber(item.count)),
      lineStyle: { width: 2 },
      areaStyle: { opacity: 0.12 },
    },
  ],
}))

const reviewOption = computed<EChartsOption>(() => ({
  color: ['#52c41a', '#ff4d4f'],
  tooltip: { trigger: 'item', formatter: '{b}：{c} 张（{d}%）' },
  legend: { bottom: 0, icon: 'circle', itemWidth: 10, itemHeight: 10 },
  series: [
    {
      type: 'pie',
      radius: ['52%', '72%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { show: false },
      data: [
        { name: '已通过', value: toNumber(review.value?.approvedCount) },
        { name: '已拒绝', value: toNumber(review.value?.rejectedCount) },
      ],
    },
  ],
}))

const categoryOption = computed<EChartsOption>(() => ({
  color: ['#1677ff'],
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
    axisLine: { lineStyle: { color: '#e5e7eb' } },
    axisLabel: {
      color: '#6b7280',
      interval: 0,
      rotate: categories.value.length > 6 ? 30 : 0,
    },
  },
  yAxis: {
    type: 'value',
    minInterval: 1,
    splitLine: { lineStyle: { color: '#f0f0f0' } },
    axisLabel: { color: '#6b7280' },
  },
  series: [
    {
      name: '图片数',
      type: 'bar',
      barMaxWidth: 32,
      itemStyle: { borderRadius: [4, 4, 0, 0] },
      data: categories.value.map((item) => toNumber(item.count)),
    },
  ],
}))

const tagOption = computed<EChartsOption>(() => {
  // 横向条形图从下往上画，反转后使用最多的标签显示在最上方
  const items = [...tags.value].reverse()
  return {
    color: ['#52c41a'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 32, top: 8, bottom: 8, containLabel: true },
    xAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f0f0f0' } },
      axisLabel: { color: '#6b7280' },
    },
    yAxis: {
      type: 'category',
      data: items.map((item) => item.tag ?? ''),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: '#4b5563' },
    },
    series: [
      {
        name: '使用次数',
        type: 'bar',
        barMaxWidth: 14,
        itemStyle: { borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', color: '#6b7280' },
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

onMounted(() => {
  fetchAll()
})
</script>

<style scoped>
#dashboardPage {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 16px;
}

.page-title {
  margin: 0;
}

.page-tip {
  margin-top: 4px;
  color: #888;
  font-size: 13px;
}

.error-alert {
  margin-bottom: 16px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.metric-card {
  height: 100%;
}

.metric-label {
  color: #6b7280;
  font-size: 13px;
}

.metric-value {
  margin-top: 8px;
  color: #111827;
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  word-break: break-all;
}

.metric-hint {
  margin-top: 6px;
  color: #9ca3af;
  font-size: 12px;
}

.chart-row {
  margin-bottom: 16px;
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
  color: #4b5563;
  font-size: 13px;
  text-align: center;
}

.review-average {
  margin-top: 4px;
  color: #6b7280;
  font-size: 13px;
  text-align: center;
}

.table-card {
  margin-top: 16px;
}

.rank {
  color: #6b7280;
}

.picture-link {
  color: #1677ff;
}
</style>
