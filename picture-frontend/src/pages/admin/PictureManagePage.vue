<template>
  <div id="pictureManagePage">
    <h2>图片管理</h2>
    <a-flex justify="space-between" align="center" style="margin-bottom: 16px">
      <a-form layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="关键词" name="searchText">
          <a-input
            v-model:value="searchParams.searchText"
            placeholder="从名称和简介搜索"
            allow-clear
          />
        </a-form-item>
        <a-form-item label="类型" name="category">
          <a-input v-model:value="searchParams.category" placeholder="请输入类型" allow-clear />
        </a-form-item>
        <a-form-item label="标签" name="tags">
          <a-select
            v-model:value="searchParams.tags"
            mode="tags"
            placeholder="请输入标签"
            style="min-width: 180px"
            allow-clear
          />
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
      <a-button type="primary" href="/add_picture" target="_blank">+ 创建图片</a-button>
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
        <!-- 标签 -->
        <template v-if="column.dataIndex === 'tags'">
          <a-space wrap>
            <a-tag v-for="tag in JSON.parse(record.tags || '[]')" :key="tag">{{ tag }}</a-tag>
          </a-space>
        </template>
        <!-- 审核状态 -->
        <template v-if="column.dataIndex === 'reviewStatus'">
          <a-tag :color="statusColor(record.reviewStatus)">
            {{ statusText(record.reviewStatus) }}
          </a-tag>
        </template>
        <!-- 图片信息 -->
        <template v-if="column.dataIndex === 'picInfo'">
          <div>格式：{{ record.picFormat }}</div>
          <div>宽度：{{ record.picWidth }}</div>
          <div>高度：{{ record.picHeight }}</div>
          <div>宽高比：{{ record.picScale }}</div>
          <div>大小：{{ (record.picSize / 1024).toFixed(2) }}KB</div>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-else-if="column.dataIndex === 'editTime'">
          {{ dayjs(record.editTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button type="link" :href="`/add_picture?id=${record.id}`" target="_blank"
              >编辑</a-button
            >
            <a-popconfirm
              title="确定删除这张图片吗？删除后不可恢复。"
              ok-text="删除"
              cancel-text="取消"
              @confirm="doDelete(record.id)"
            >
              <a-button type="link" danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { message } from 'ant-design-vue'
import { deletePicture, listPictureByPage } from '@/api/pictureController'
import { getThumbnailUrl } from '@/utils'
import dayjs from 'dayjs'
const columns = [
  {
    title: 'id',
    dataIndex: 'id',
    width: 80,
  },
  {
    title: '图片',
    dataIndex: 'url',
  },
  {
    title: '名称',
    dataIndex: 'name',
  },
  {
    title: '简介',
    dataIndex: 'introduction',
    ellipsis: true,
  },
  {
    title: '类型',
    dataIndex: 'category',
  },
  {
    title: '标签',
    dataIndex: 'tags',
  },
  {
    title: '图片信息',
    dataIndex: 'picInfo',
  },
  {
    title: '用户 id',
    dataIndex: 'userId',
    width: 80,
  },
  {
    title: '审核状态',
    dataIndex: 'reviewStatus',
    width: 110,
  },
  {
    title: '下载次数',
    dataIndex: 'downloadCount',
    width: 100,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '编辑时间',
    dataIndex: 'editTime',
  },
  {
    title: '操作',
    key: 'action',
  },
]

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

// 数据
const dataList = ref<API.Picture[]>([])
const total = ref(0)
const loading = ref(false)

// 搜索条件
const searchParams = reactive<API.PictureQueryRequest>({
  current: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 分页参数
const pagination = computed(() => {
  return {
    current: searchParams.current ?? 1,
    pageSize: searchParams.pageSize ?? 10,
    total: total.value,
    showSizeChanger: true,
    showTotal: (total: number) => `共 ${total} 条`,
  }
})

// 获取数据
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listPictureByPage({
      ...searchParams,
    })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取数据失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('获取数据失败：' + (error.message || '系统错误'))
  } finally {
    loading.value = false
  }
}

// 获取数据
const doSearch = () => {
  // 重置搜索条件
  searchParams.current = 1
  fetchData()
}

// 表格变化处理
const doTableChange = (page: any) => {
  searchParams.current = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 删除数据
const doDelete = async (id: string) => {
  if (!id) {
    return
  }
  try {
    const res = await deletePicture({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      // 删除当前页最后一条时回到上一页
      if (dataList.value.length <= 1 && (searchParams.current ?? 1) > 1) {
        searchParams.current = (searchParams.current ?? 1) - 1
      }
      // 刷新数据
      await fetchData()
    } else {
      message.error('删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('删除失败：' + (error.message || '系统错误'))
  }
}
// 页面加载时请求一次
onMounted(() => {
  fetchData()
})
</script>
