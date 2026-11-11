<template>
  <div id="myPicturesPage">
    <div class="page-header">
      <div>
        <h2>我的图片</h2>
        <div class="desc">查看自己上传的全部图片，包括待审核和已拒绝的图片</div>
      </div>
      <a-button type="primary" @click="goUpload">上传图片</a-button>
    </div>

    <a-tabs v-model:activeKey="activeStatus" @change="handleStatusChange">
      <a-tab-pane key="all" tab="全部" />
      <a-tab-pane key="0" tab="待审核" />
      <a-tab-pane key="1" tab="已通过" />
      <a-tab-pane key="2" tab="已拒绝" />
    </a-tabs>

    <a-table
      :data-source="dataList"
      :columns="columns"
      :loading="loading"
      :pagination="pagination"
      row-key="id"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'cover'">
          <img class="cover" :src="getThumbnailUrl(record.url)" :alt="record.name" />
        </template>
        <template v-else-if="column.key === 'name'">
          <div>{{ record.name || '未命名' }}</div>
          <div class="sub-text">{{ record.category || '默认分类' }}</div>
        </template>
        <template v-else-if="column.key === 'picSize'">
          {{ formatSize(record.picSize) }}
        </template>
        <template v-else-if="column.key === 'reviewStatus'">
          <a-space>
            <a-tag :color="statusColor(record.reviewStatus)">
              {{ statusText(record.reviewStatus) }}
            </a-tag>
            <a-tooltip
              v-if="record.reviewStatus === 2 && record.reviewMessage"
              :title="record.reviewMessage"
            >
              <info-circle-outlined class="status-icon" />
            </a-tooltip>
          </a-space>
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button type="link" size="small" @click="viewPicture(record)">查看</a-button>
            <a-button type="link" size="small" @click="editPictureItem(record)">编辑</a-button>
            <a-popconfirm title="确定删除这张图片吗？" @confirm="doDelete(record)">
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { InfoCircleOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { deletePicture } from '@/api/pictureController'
import { listMyPictureVoByPage } from '@/api/pictureExtra'
import { formatSize, getThumbnailUrl } from '@/utils'

const router = useRouter()

const columns = [
  { title: '封面', key: 'cover', width: 110 },
  { title: '名称', key: 'name' },
  { title: '大小', key: 'picSize', width: 100 },
  { title: '状态', key: 'reviewStatus', width: 130 },
  { title: '上传时间', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 190 },
]

const query = reactive<API.PictureQueryRequest>({
  current: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
})

const activeStatus = ref('all')
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(false)

const pagination = computed(() => {
  return {
    current: query.current ?? 1,
    pageSize: query.pageSize ?? 10,
    total: total.value,
    showSizeChanger: false,
    onChange: (page: number) => {
      query.current = page
      fetchData()
    },
  }
})

const statusText = (status?: number) => {
  const map: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已拒绝' }
  return map[status ?? 0] ?? '未知'
}

const statusColor = (status?: number) => {
  const map: Record<number, string> = { 0: 'gold', 1: 'green', 2: 'red' }
  return map[status ?? 0] ?? 'default'
}

const formatTime = (value?: string) => {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}

const fetchData = async () => {
  loading.value = true
  try {
    const params: API.PictureQueryRequest = { ...query }
    if (activeStatus.value === 'all') {
      delete params.reviewStatus
    } else {
      params.reviewStatus = Number(activeStatus.value)
    }
    const res = await listMyPictureVoByPage(params)
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取图片列表失败，' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('获取图片列表失败，' + (error.message || '系统错误'))
  } finally {
    loading.value = false
  }
}

const handleStatusChange = () => {
  query.current = 1
  fetchData()
}

const goUpload = () => {
  router.push('/add_picture')
}

const viewPicture = (record: API.PictureVO) => {
  router.push(`/picture/${record.id}`)
}

const editPictureItem = (record: API.PictureVO) => {
  router.push(`/add_picture?id=${record.id}`)
}

const doDelete = async (record: API.PictureVO) => {
  if (!record.id) {
    return
  }
  try {
    const res = await deletePicture({ id: record.id })
    if (res.data.code === 0) {
      message.success('删除成功')
      // 删除当前页最后一条时回到上一页
      if (dataList.value.length === 1 && (query.current ?? 1) > 1) {
        query.current = (query.current ?? 1) - 1
      }
      await fetchData()
    } else {
      message.error('删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('删除失败：' + (error.message || '系统错误'))
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#myPicturesPage {
  max-width: 1100px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.page-header h2 {
  margin-bottom: 4px;
}

.desc {
  color: #999;
  font-size: 13px;
}

.cover {
  width: 80px;
  height: 60px;
  object-fit: cover;
  border-radius: 4px;
}

.sub-text {
  color: #999;
  font-size: 12px;
}

.status-icon {
  color: #faad14;
}
</style>
