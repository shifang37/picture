<template>
  <div id="pictureRecyclePage">
    <h2>图片回收站</h2>
    <a-alert
      type="info"
      show-icon
      style="margin-bottom: 16px"
      message="回收站中的图片会在保留期满后自动彻底删除（默认 7 天，含 COS 文件）；审核日志按合规要求长期保留。"
    />
    <a-flex justify="space-between" align="center" style="margin-bottom: 16px">
      <a-form layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="名称" name="name">
          <a-input v-model:value="searchParams.name" placeholder="请输入图片名称" allow-clear />
        </a-form-item>
        <a-form-item label="类型" name="category">
          <a-input v-model:value="searchParams.category" placeholder="请输入类型" allow-clear />
        </a-form-item>
        <a-form-item label="审核状态" name="reviewStatus">
          <a-select
            v-model:value="searchParams.reviewStatus"
            :options="reviewStatusOptions"
            placeholder="全部状态"
            style="min-width: 140px"
            allow-clear
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit">搜索</a-button>
        </a-form-item>
      </a-form>
      <a-button @click="fetchData">刷新</a-button>
    </a-flex>
    <a-table
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      :loading="loading"
      row-key="id"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'url'">
          <a-image :src="getThumbnailUrl(record.url)" :width="120" />
        </template>
        <template v-if="column.dataIndex === 'tags'">
          <a-space wrap>
            <a-tag v-for="tag in parseTags(record.tags)" :key="tag">{{ tag }}</a-tag>
          </a-space>
        </template>
        <template v-if="column.dataIndex === 'reviewStatus'">
          <a-tag :color="statusColor(record.reviewStatus)">
            {{ statusText(record.reviewStatus) }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'deletedAt'">
          {{ formatTime(record.deletedAt) }}
        </template>
        <template v-else-if="column.dataIndex === 'deletedBy'">
          {{ record.deletedBy ? `用户#${record.deletedBy}` : '-' }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-popconfirm
            title="确定恢复这张图片吗？已通过审核的图片会立即重新公开。"
            ok-text="恢复"
            cancel-text="取消"
            @confirm="doRestore(record.id)"
          >
            <a-button type="link">恢复</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { listRecyclePictureByPage, restorePicture } from '@/api/pictureExtra'
import { getThumbnailUrl } from '@/utils'
import dayjs from 'dayjs'

const parseTags = (tags?: string) => {
  if (!tags) {
    return []
  }
  try {
    const parsed = JSON.parse(tags)
    return Array.isArray(parsed) ? (parsed as string[]) : []
  } catch {
    return []
  }
}

const formatTime = (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-')

const reviewStatusOptions = [
  { value: 0, label: '待审核' },
  { value: 1, label: '已通过' },
  { value: 2, label: '已拒绝' },
]

const statusText = (status?: number) => {
  const map: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已拒绝' }
  return map[status ?? 0] ?? '未知'
}

const statusColor = (status?: number) => {
  const map: Record<number, string> = { 0: 'orange', 1: 'green', 2: 'red' }
  return map[status ?? 0] ?? 'default'
}

const columns = [
  { title: 'id', dataIndex: 'id', width: 80 },
  { title: '图片', dataIndex: 'url', width: 150 },
  { title: '名称', dataIndex: 'name' },
  { title: '简介', dataIndex: 'introduction', ellipsis: true },
  { title: '类型', dataIndex: 'category' },
  { title: '标签', dataIndex: 'tags' },
  { title: '用户 id', dataIndex: 'userId', width: 90 },
  { title: '审核状态', dataIndex: 'reviewStatus', width: 110 },
  { title: '删除时间', dataIndex: 'deletedAt', width: 180 },
  { title: '删除人', dataIndex: 'deletedBy', width: 110 },
  { title: '操作', key: 'action', width: 100 },
]

const dataList = ref<API.Picture[]>([])
const total = ref(0)
const loading = ref(false)

const searchParams = reactive<API.PictureQueryRequest>({
  current: 1,
  pageSize: 10,
})

const pagination = computed(() => ({
  current: searchParams.current ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showTotal: (value: number) => `共 ${value} 条`,
}))

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listRecyclePictureByPage({ ...searchParams })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取回收站数据失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('获取回收站数据失败：' + (error.message || '系统错误'))
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

const doRestore = async (id: string) => {
  if (!id) {
    return
  }
  try {
    const res = await restorePicture(id)
    if (res.data.code === 0) {
      message.success('恢复成功')
      // 恢复当前页最后一条时回到上一页
      if (dataList.value.length <= 1 && (searchParams.current ?? 1) > 1) {
        searchParams.current = (searchParams.current ?? 1) - 1
      }
      await fetchData()
    } else {
      message.error('恢复失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('恢复失败：' + (error.message || '系统错误'))
  }
}

onMounted(() => {
  fetchData()
})
</script>
