<template>
  <div id="albumDetailPage">
    <div v-if="loading" class="state-block" data-testid="album-loading">
      <a-spin size="large" />
    </div>

    <a-result
      v-else-if="notFound"
      status="404"
      title="专辑不存在"
      sub-title="专辑可能已被删除，或者是私密专辑"
      data-testid="album-not-found"
    >
      <template #extra>
        <a-button type="primary" @click="router.push('/')">返回首页</a-button>
      </template>
    </a-result>

    <template v-else>
      <a-card class="album-header" :bordered="false" data-testid="album-header">
        <div class="album-header-main">
          <div class="album-cover">
            <img
              v-if="album.coverUrl"
              :src="getThumbnailUrl(album.coverUrl, 400)"
              :alt="album.name || '专辑封面'"
            />
            <div v-else class="album-cover-empty">暂无封面</div>
          </div>
          <div class="album-info">
            <div class="album-title">
              <h2 data-testid="album-name">{{ album.name || '未命名专辑' }}</h2>
              <a-tag :color="album.isPublic === 1 ? 'green' : 'default'">
                {{ album.isPublic === 1 ? '公开' : '私密' }}
              </a-tag>
            </div>
            <div class="album-desc">{{ album.description || '暂无简介' }}</div>
            <div class="album-meta">
              <router-link v-if="album.user?.id" :to="`/author/${album.user.id}`">
                {{ album.user.userName || '未知作者' }}
              </router-link>
              <span v-else>{{ album.user?.userName || '未知作者' }}</span>
              <span>公开 {{ formatCount(album.publicCount) }}</span>
              <span v-if="album.canManage">共 {{ formatCount(album.pictureCount) }}</span>
            </div>
            <a-space wrap class="album-actions">
              <a-button @click="doShare">分享</a-button>
              <template v-if="album.canManage">
                <a-button @click="openEditor">编辑专辑</a-button>
                <a-radio-group v-model:value="mode" size="small" button-style="solid">
                  <a-radio-button value="view">浏览</a-radio-button>
                  <a-radio-button value="manage">管理</a-radio-button>
                </a-radio-group>
              </template>
            </a-space>
          </div>
        </div>
      </a-card>

      <!-- 浏览模式：瀑布流 -->
      <template v-if="mode === 'view'">
        <div v-if="initialLoading" class="state-block" data-testid="album-pictures-loading">
          <a-spin />
        </div>
        <div v-else-if="!pictures.length && loadError" class="state-block">
          <a-empty :description="loadError">
            <a-button type="primary" @click="retryLoad">重试</a-button>
          </a-empty>
        </div>
        <div v-else-if="!pictures.length" class="state-block" data-testid="album-empty">
          <a-empty :description="album.canManage ? '专辑还没有图片，去「我的图片」里加入吧' : '这个专辑还没有公开作品'" />
        </div>
        <PictureWaterfall v-else :pictures="pictures" @open="openPicture" />
        <div v-if="pictures.length" class="load-more" data-testid="album-load-more">
          <template v-if="fetching">
            <a-spin size="small" />
            <span>正在加载更多...</span>
          </template>
          <template v-else-if="loadError">
            <span class="load-more-error">{{ loadError }}</span>
            <a-button size="small" type="primary" ghost @click="retryLoad">重试</a-button>
          </template>
          <span v-else-if="!hasMore" class="load-more-end">没有更多了</span>
        </div>
      </template>

      <!-- 管理模式：表格（仅作者/管理员） -->
      <a-card v-else title="专辑作品管理" :bordered="false">
        <a-table
          :data-source="managePictures"
          :columns="manageColumns"
          :loading="manageLoading"
          :pagination="managePagination"
          row-key="id"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'cover'">
              <img class="manage-cover" :src="getThumbnailUrl(record.url, 160)" :alt="record.name" />
            </template>
            <template v-else-if="column.key === 'name'">
              <div>{{ record.name || '未命名' }}</div>
              <div class="sub-text">{{ record.category || '默认分类' }}</div>
            </template>
            <template v-else-if="column.key === 'reviewStatus'">
              <a-tag :color="statusColor(record.reviewStatus)">
                {{ statusText(record.reviewStatus) }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <a-button
                  type="link"
                  size="small"
                  :disabled="String(album.coverPictureId) === String(record.id)"
                  @click="setCover(record)"
                >
                  设为封面
                </a-button>
                <a-popconfirm
                  title="从专辑移除这张图片？图片本身不会被删除。"
                  ok-text="移除"
                  cancel-text="取消"
                  @confirm="removePicture(record)"
                >
                  <a-button type="link" size="small" danger>移除</a-button>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
      </a-card>
    </template>

    <a-modal
      v-model:open="editorOpen"
      title="编辑专辑"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleSave"
    >
      <a-form layout="vertical">
        <a-form-item label="专辑名称" required>
          <a-input v-model:value="form.name" :maxlength="64" placeholder="给专辑起个名字" />
        </a-form-item>
        <a-form-item label="简介">
          <a-textarea
            v-model:value="form.description"
            :rows="3"
            :maxlength="512"
            show-count
            placeholder="简单介绍一下这个专辑"
          />
        </a-form-item>
        <a-form-item label="公开">
          <a-switch
            v-model:checked="form.isPublic"
            checked-children="公开"
            un-checked-children="私密"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useInfiniteScroll } from '@vueuse/core'
import {
  getAlbum,
  listAlbumPicturePage,
  removeAlbumPictures,
  updateAlbum,
  type AlbumDetailVO,
} from '@/api/albumExtra'
import { formatCount } from '@/utils/interaction'
import { getThumbnailUrl } from '@/utils'
import PictureWaterfall from '@/components/PictureWaterfall.vue'

const PAGE_SIZE = 20

const props = defineProps<{
  id: string
}>()

const router = useRouter()

const loading = ref(true)
const notFound = ref(false)
const album = ref<AlbumDetailVO>({})

const mode = ref<'view' | 'manage'>('view')
const pictures = ref<API.PictureVO[]>([])
const total = ref(0)
const currentPage = ref(0)
const hasMore = ref(false)
const fetching = ref(false)
const initialLoading = ref(true)
const loadError = ref('')

// 管理模式：单独做服务端分页，避免和无限滚动的列表状态互相干扰
const manageMode = computed(() => mode.value === 'manage' && album.value.canManage === true)
const managePictures = ref<API.PictureVO[]>([])
const manageTotal = ref(0)
const manageCurrent = ref(1)
const managePageSize = ref(10)
const manageLoading = ref(false)
const manageColumns = [
  { title: '封面', key: 'cover', width: 100 },
  { title: '名称', key: 'name' },
  { title: '状态', key: 'reviewStatus', width: 110 },
  { title: '操作', key: 'action', width: 200 },
]
const managePagination = computed(() => ({
  current: manageCurrent.value,
  pageSize: managePageSize.value,
  total: manageTotal.value,
  showSizeChanger: false,
  onChange: (page: number) => {
    manageCurrent.value = page
    fetchManagePictures()
  },
}))

const editorOpen = ref(false)
const saving = ref(false)
const form = reactive({ name: '', description: '', isPublic: false })

const fetchAlbum = async () => {
  loading.value = true
  notFound.value = false
  try {
    const res = await getAlbum(props.id)
    if (res.data.code === 0 && res.data.data) {
      album.value = res.data.data
    } else {
      notFound.value = true
    }
  } catch {
    notFound.value = true
  } finally {
    loading.value = false
  }
}

let requestSeq = 0

const fetchPage = async (reset = false) => {
  if (!reset && (fetching.value || !hasMore.value)) {
    return
  }
  const seq = (requestSeq += 1)
  fetching.value = true
  loadError.value = ''
  if (reset) {
    pictures.value = []
    total.value = 0
    currentPage.value = 0
    hasMore.value = false
    initialLoading.value = true
  }
  const page = currentPage.value + 1
  try {
    const res = await listAlbumPicturePage({
      albumId: props.id,
      current: page,
      pageSize: PAGE_SIZE,
    })
    if (seq !== requestSeq) {
      return
    }
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records ?? []
      const newTotal = Number(res.data.data.total) || 0
      // 滚动期间若有图片移入/移除，后续页会整体位移，按 id 去重
      const seenIds = new Set(pictures.value.map((picture) => String(picture.id)))
      const uniqueRecords = records.filter((picture) => {
        const id = String(picture.id)
        if (!picture.id || seenIds.has(id)) {
          return false
        }
        seenIds.add(id)
        return true
      })
      pictures.value = [...pictures.value, ...uniqueRecords]
      total.value = newTotal
      currentPage.value = page
      hasMore.value = false
      if (records.length >= PAGE_SIZE && page * PAGE_SIZE < newTotal) {
        nextTick(() => {
          hasMore.value = true
        })
      }
    } else {
      loadError.value = res.data.message || '加载作品失败'
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

const retryLoad = () => {
  if (currentPage.value === 0) {
    fetchPage(true)
  } else {
    loadError.value = ''
    fetchPage()
  }
}

const fetchManagePictures = async () => {
  manageLoading.value = true
  try {
    const res = await listAlbumPicturePage({
      albumId: props.id,
      current: manageCurrent.value,
      pageSize: managePageSize.value,
    })
    if (res.data.code === 0 && res.data.data) {
      managePictures.value = res.data.data.records ?? []
      manageTotal.value = Number(res.data.data.total) || 0
    } else {
      message.error('加载专辑作品失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('加载专辑作品失败：' + (error?.message || '系统错误'))
  } finally {
    manageLoading.value = false
  }
}

// 切到管理模式时按需加载；切回浏览模式继续用无限滚动列表
watch(mode, (value) => {
  if (value === 'manage' && album.value.canManage) {
    manageCurrent.value = 1
    fetchManagePictures()
  }
})

useInfiniteScroll(typeof window === 'undefined' ? null : window, () => fetchPage(), {
  distance: 400,
  interval: 200,
  canLoadMore: () =>
    !manageMode.value && hasMore.value && !fetching.value && !loadError.value,
})

const openPicture = (picture: API.PictureVO) => {
  if (picture.id) {
    router.push(`/picture/${picture.id}`)
  }
}

const statusText = (status?: number) => {
  const map: Record<number, string> = { 0: '待审核', 1: '已通过', 2: '已拒绝' }
  return map[status ?? 0] ?? '未知'
}

const statusColor = (status?: number) => {
  const map: Record<number, string> = { 0: 'gold', 1: 'green', 2: 'red' }
  return map[status ?? 0] ?? 'default'
}

const setCover = async (picture: API.PictureVO) => {
  if (!picture.id) {
    return
  }
  try {
    const res = await updateAlbum({ id: props.id, coverPictureId: picture.id })
    if (res.data.code === 0) {
      message.success('封面已更新')
      await fetchAlbum()
      await fetchManagePictures()
    } else {
      message.error('设置封面失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('设置封面失败：' + (error?.message || '系统错误'))
  }
}

const removePicture = async (picture: API.PictureVO) => {
  if (!picture.id) {
    return
  }
  try {
    const res = await removeAlbumPictures(props.id, [picture.id])
    if (res.data.code === 0) {
      message.success('已从专辑移除，图片本身不受影响')
      // 当前页删空时回到上一页
      if (managePictures.value.length === 1 && manageCurrent.value > 1) {
        manageCurrent.value -= 1
      }
      await fetchAlbum()
      await fetchManagePictures()
      await fetchPage(true)
    } else {
      message.error('移除失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('移除失败：' + (error?.message || '系统错误'))
  }
}

const openEditor = () => {
  form.name = album.value.name ?? ''
  form.description = album.value.description ?? ''
  form.isPublic = album.value.isPublic === 1
  editorOpen.value = true
}

const handleSave = async () => {
  const name = form.name.trim()
  if (!name) {
    message.warning('专辑名称不能为空')
    return
  }
  saving.value = true
  try {
    const res = await updateAlbum({
      id: props.id,
      name,
      description: form.description.trim(),
      isPublic: form.isPublic ? 1 : 0,
    })
    if (res.data.code === 0) {
      message.success('专辑已更新')
      editorOpen.value = false
      await fetchAlbum()
    } else {
      message.error('保存失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('保存失败：' + (error?.message || '系统错误'))
  } finally {
    saving.value = false
  }
}

const doShare = async () => {
  const shareUrl = window.location.href
  try {
    await navigator.clipboard.writeText(shareUrl)
    message.success('链接已复制，可以分享给他人了')
  } catch {
    message.info('复制失败，请手动复制浏览器地址栏链接：' + shareUrl)
  }
}

onMounted(async () => {
  await fetchAlbum()
  if (!notFound.value) {
    fetchPage(true)
  } else {
    initialLoading.value = false
  }
})
</script>

<style scoped>
#albumDetailPage {
  max-width: 1200px;
  margin: 0 auto;
}

.album-header {
  margin-bottom: 24px;
  background: #fafafa;
}

.album-header-main {
  display: flex;
  gap: 20px;
}

.album-cover {
  flex: 0 0 160px;
  width: 160px;
  height: 120px;
  overflow: hidden;
  border-radius: 8px;
  background: #eee;
}

.album-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.album-cover-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #bbb;
  font-size: 13px;
}

.album-info {
  flex: 1;
  min-width: 0;
}

.album-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.album-title h2 {
  margin: 0;
}

.album-desc {
  margin-top: 8px;
  color: #666;
  white-space: pre-wrap;
  word-break: break-word;
}

.album-meta {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  color: #999;
  font-size: 13px;
}

.album-actions {
  margin-top: 12px;
}

.manage-cover {
  width: 72px;
  height: 54px;
  object-fit: cover;
  border-radius: 4px;
}

.sub-text {
  color: #999;
  font-size: 12px;
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 64px 0;
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
