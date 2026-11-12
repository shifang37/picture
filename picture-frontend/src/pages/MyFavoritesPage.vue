<template>
  <div id="myFavoritesPage">
    <div class="page-header">
      <div>
        <h2>我的收藏</h2>
        <div class="desc">收藏与点赞过的图片，只展示已经通过审核的作品</div>
      </div>
      <a-button type="primary" @click="goHome">去逛逛</a-button>
    </div>

    <a-tabs v-model:activeKey="activeType" @change="handleTypeChange">
      <a-tab-pane key="favorite" tab="我的收藏" />
      <a-tab-pane key="like" tab="我的点赞" />
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
        <template v-else-if="column.key === 'author'">
          {{ record.user?.userName || '未知作者' }}
        </template>
        <template v-else-if="column.key === 'counts'">
          <a-space>
            <span>收藏 {{ formatCount(record.favoriteCount) }}</span>
            <span>点赞 {{ formatCount(record.likeCount) }}</span>
          </a-space>
        </template>
        <template v-else-if="column.key === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-button type="link" size="small" @click="viewPicture(record)">查看</a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { listMyInteractionPage, type InteractionType } from '@/api/interactionExtra'
import { formatCount } from '@/utils/interaction'
import { getThumbnailUrl } from '@/utils'

const router = useRouter()

const columns = [
  { title: '封面', key: 'cover', width: 110 },
  { title: '名称', key: 'name' },
  { title: '作者', key: 'author', width: 140 },
  { title: '互动', key: 'counts', width: 200 },
  { title: '发布时间', key: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 100 },
]

const activeType = ref<InteractionType>('favorite')
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)
const loading = ref(false)

const pagination = computed(() => ({
  current: current.value,
  pageSize: pageSize.value,
  total: total.value,
  showSizeChanger: false,
  onChange: (page: number) => {
    current.value = page
    fetchData()
  },
}))

const formatTime = (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-')

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listMyInteractionPage({
      type: activeType.value,
      current: current.value,
      pageSize: pageSize.value,
    })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取列表失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('获取列表失败：' + (error?.message || '系统错误'))
  } finally {
    loading.value = false
  }
}

const handleTypeChange = () => {
  current.value = 1
  fetchData()
}

const viewPicture = (record: API.PictureVO) => {
  router.push(`/picture/${record.id}`)
}

const goHome = () => {
  router.push('/')
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#myFavoritesPage {
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
</style>
