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

    <div class="batch-toolbar">
      <span class="selected-text">已选择 {{ selectedRowKeys.length }} 张</span>
      <a-space>
        <a-button :disabled="!selectedRowKeys.length" @click="batchEditOpen = true">
          批量编辑
        </a-button>
        <a-button :disabled="!selectedRowKeys.length" @click="addToAlbumOpen = true">
          加入专辑
        </a-button>
        <a-popconfirm
          title="确定删除选中的图片吗？删除后进入回收站，可在保留期内由管理员恢复。"
          ok-text="删除"
          cancel-text="取消"
          :disabled="!selectedRowKeys.length"
          @confirm="doBatchDelete"
        >
          <a-button danger :disabled="!selectedRowKeys.length">批量删除</a-button>
        </a-popconfirm>
        <a-button v-if="selectedRowKeys.length" type="link" @click="clearSelection">
          清空选择
        </a-button>
      </a-space>
    </div>

    <a-table
      :data-source="dataList"
      :columns="columns"
      :loading="loading"
      :pagination="pagination"
      :row-selection="rowSelection"
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
            <a-popconfirm
              title="确定删除这张图片吗？删除后进入回收站，可在保留期内由管理员恢复。"
              @confirm="doDelete(record)"
            >
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <BatchEditModal
      v-model:open="batchEditOpen"
      :ids="selectedRowKeys"
      @success="handleBatchEdited"
    />
    <AddToAlbumModal
      v-model:open="addToAlbumOpen"
      :picture-ids="selectedRowKeys"
      @success="clearSelection"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { InfoCircleOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { deletePicture } from '@/api/pictureController'
import { batchDeletePictures, listMyPictureVoByPage } from '@/api/pictureExtra'
import { formatSize, getThumbnailUrl } from '@/utils'
import BatchEditModal from '@/components/BatchEditModal.vue'
import AddToAlbumModal from '@/components/AddToAlbumModal.vue'

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

// 批量操作：表格多选与批量编辑弹窗
const selectedRowKeys = ref<string[]>([])
const batchEditOpen = ref(false)
const addToAlbumOpen = ref(false)
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  preserveSelectedRowKeys: false,
  onChange: (keys: (string | number)[]) => {
    selectedRowKeys.value = keys.map((key) => String(key))
  },
}))

const clearSelection = () => {
  selectedRowKeys.value = []
}

const pagination = computed(() => {
  return {
    current: query.current ?? 1,
    pageSize: query.pageSize ?? 10,
    total: total.value,
    showSizeChanger: false,
    onChange: (page: number) => {
      query.current = page
      clearSelection()
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
  clearSelection()
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

const doBatchDelete = async () => {
  if (!selectedRowKeys.value.length) {
    return
  }
  const ids = [...selectedRowKeys.value]
  try {
    const res = await batchDeletePictures(ids)
    if (res.data.code === 0) {
      const result = res.data.data
      if (result?.failCount) {
        message.warning(
          `已删除 ${result.successCount ?? 0} 张，失败 ${result.failCount}：${result.errors?.join('；')}`,
        )
      } else {
        message.success(`已删除 ${result?.successCount ?? 0} 张图片`)
      }
      // 当前页可能被删空，回到上一页避免出现空白页
      const deletedCount = result?.successCount ?? 0
      if (deletedCount >= dataList.value.length && (query.current ?? 1) > 1) {
        query.current = (query.current ?? 1) - 1
      }
      clearSelection()
      await fetchData()
    } else {
      message.error('批量删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('批量删除失败：' + (error.message || '系统错误'))
  }
}

// 批量编辑成功：清空选择并刷新当前页
const handleBatchEdited = async () => {
  clearSelection()
  await fetchData()
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

.batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.selected-text {
  color: #666;
  font-size: 13px;
}
</style>
