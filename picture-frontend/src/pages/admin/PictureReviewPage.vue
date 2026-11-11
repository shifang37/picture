<template>
  <div id="pictureReviewPage">
    <h2 style="margin-bottom: 16px">图片审核</h2>

    <a-flex justify="space-between" align="center" style="margin-bottom: 16px" wrap="wrap" gap="12">
      <a-form layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="关键词" name="searchText">
          <a-input
            v-model:value="searchParams.searchText"
            placeholder="从名称和简介搜索"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="分类" name="category">
          <a-input v-model:value="searchParams.category" placeholder="请输入分类" allow-clear />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit">搜索</a-button>
        </a-form-item>
      </a-form>

      <a-space>
        <a-radio-group
          v-model:value="searchParams.reviewStatus"
          button-style="solid"
          @change="doSearch"
        >
          <a-radio-button :value="0">待审核</a-radio-button>
          <a-radio-button :value="1">已通过</a-radio-button>
          <a-radio-button :value="2">已拒绝</a-radio-button>
        </a-radio-group>
        <a-button @click="fetchData">刷新</a-button>
      </a-space>
    </a-flex>

    <a-table
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      :loading="loading"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'url'">
          <a-image :src="getThumbnailUrl(record.url)" :width="120" />
        </template>
        <template v-else-if="column.dataIndex === 'tags'">
          <a-space wrap>
            <a-tag v-for="tag in parseTags(record.tags)" :key="tag">{{ tag }}</a-tag>
          </a-space>
        </template>
        <template v-else-if="column.dataIndex === 'reviewStatus'">
          <a-tag :color="statusColor(record.reviewStatus)">
            {{ statusText(record.reviewStatus) }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'reviewTime'">
          {{ record.reviewTime ? dayjs(record.reviewTime).format('YYYY-MM-DD HH:mm:ss') : '-' }}
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space v-if="record.reviewStatus === 0">
            <a-button type="link" @click="openReview(record, 1)">通过</a-button>
            <a-button type="link" danger @click="openReview(record, 2)">拒绝</a-button>
          </a-space>
          <a-space v-else>
            <span class="reviewed-text">已审核</span>
            <a-button type="link" size="small" @click="openHistory(record)">历史</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="reviewModalOpen"
      :title="reviewTitle"
      :confirm-loading="submitting"
      ok-text="确认"
      cancel-text="取消"
      @ok="submitReview"
    >
      <a-form layout="vertical">
        <a-form-item label="审核意见">
          <a-textarea
            v-model:value="reviewForm.reviewMessage"
            :rows="3"
            :maxlength="512"
            show-count
            placeholder="通过可留空，拒绝建议填写原因（最多 512 字）"
            allow-clear
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="historyModalOpen" title="审核历史" :footer="null">
      <a-spin :spinning="historyLoading">
        <a-empty v-if="!historyLoading && historyList.length === 0" description="暂无审核记录" />
        <a-timeline v-else>
          <a-timeline-item
            v-for="reviewLog in historyList"
            :key="reviewLog.id"
            :color="reviewLog.reviewStatus === 1 ? 'green' : 'red'"
          >
            <div class="history-title">
              {{ reviewLog.reviewStatus === 1 ? '通过' : '拒绝' }} ·
              {{ formatTime(reviewLog.createTime) }}
            </div>
            <div v-if="reviewLog.reviewMessage" class="history-message">
              审核意见：{{ reviewLog.reviewMessage }}
            </div>
          </a-timeline-item>
        </a-timeline>
      </a-spin>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { listPictureByPage, doPictureReview } from '@/api/pictureController'
import { listPictureReviewLogs, type PictureReviewLog } from '@/api/pictureExtra'
import { getThumbnailUrl } from '@/utils'

const columns = [
  { title: 'id', dataIndex: 'id', width: 180 },
  { title: '图片', dataIndex: 'url', width: 140 },
  { title: '名称', dataIndex: 'name' },
  { title: '简介', dataIndex: 'introduction', ellipsis: true },
  { title: '分类', dataIndex: 'category' },
  { title: '标签', dataIndex: 'tags' },
  { title: '状态', dataIndex: 'reviewStatus', width: 100 },
  { title: '审核时间', dataIndex: 'reviewTime', width: 180 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 140 },
]

// 数据
const dataList = ref<API.Picture[]>([])
const total = ref(0)
const loading = ref(false)

// 搜索条件，默认只看待审核
const searchParams = reactive<API.PictureQueryRequest>({
  current: 1,
  pageSize: 10,
  reviewStatus: 0,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 分页参数
const pagination = computed(() => ({
  current: searchParams.current ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showTotal: (t: number) => `共 ${t} 条`,
}))

// 获取数据
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listPictureByPage({ ...searchParams })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取数据失败，' + res.data.message)
    }
  } finally {
    loading.value = false
  }
}

const doSearch = () => {
  searchParams.current = 1
  fetchData()
}

const doTableChange = (page: any) => {
  searchParams.current = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 标签是数据库里的 JSON 字符串
const parseTags = (tags?: string) => {
  if (!tags) return []
  try {
    return JSON.parse(tags) as string[]
  } catch (e) {
    return []
  }
}

const statusText = (status?: number) => {
  const map: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已拒绝' }
  return map[status ?? 0] ?? '未知'
}

const statusColor = (status?: number) => {
  const map: Record<number, string> = { 0: 'orange', 1: 'green', 2: 'red' }
  return map[status ?? 0] ?? 'default'
}

// 审核弹窗
const reviewModalOpen = ref(false)
const submitting = ref(false)
const currentRecord = ref<API.Picture>()
const reviewForm = reactive({
  reviewStatus: 1,
  reviewMessage: '',
})

const reviewTitle = computed(() => (reviewForm.reviewStatus === 1 ? '通过审核' : '拒绝审核'))

const openReview = (record: API.Picture, reviewStatus: number) => {
  // 已审核的图片不允许重复操作，避免无效请求
  if (record.reviewStatus !== 0) {
    message.info('该图片已审核，无需重复操作')
    return
  }
  currentRecord.value = record
  reviewForm.reviewStatus = reviewStatus
  reviewForm.reviewMessage = ''
  reviewModalOpen.value = true
}

const submitReview = async () => {
  const id = currentRecord.value?.id
  if (!id) {
    return
  }
  if (reviewForm.reviewMessage.length > 512) {
    message.error('审核意见不能超过 512 个字符')
    return
  }
  submitting.value = true
  try {
    const res = await doPictureReview({
      id,
      reviewStatus: reviewForm.reviewStatus,
      reviewMessage: reviewForm.reviewMessage,
    })
    if (res.data.code === 0) {
      message.success(reviewForm.reviewStatus === 1 ? '已通过审核' : '已拒绝')
      reviewModalOpen.value = false
      fetchData()
    } else {
      message.error('审核失败：' + res.data.message)
    }
  } catch (e: any) {
    message.error('审核失败：' + (e.message || '系统错误'))
  } finally {
    submitting.value = false
  }
}

// 审核历史
const historyModalOpen = ref(false)
const historyLoading = ref(false)
const historyList = ref<PictureReviewLog[]>([])

const formatTime = (value?: string) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
}

const openHistory = async (record: API.Picture) => {
  if (!record.id) {
    return
  }
  historyModalOpen.value = true
  historyLoading.value = true
  historyList.value = []
  try {
    const res = await listPictureReviewLogs(record.id)
    if (res.data.code === 0) {
      historyList.value = res.data.data ?? []
    } else {
      message.error('获取审核历史失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('获取审核历史失败：' + (e.message || '系统错误'))
  } finally {
    historyLoading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.reviewed-text {
  color: #999;
}

.history-title {
  font-weight: 500;
}

.history-message {
  color: #666;
  font-size: 13px;
  margin-top: 4px;
  white-space: pre-wrap;
}
</style>
