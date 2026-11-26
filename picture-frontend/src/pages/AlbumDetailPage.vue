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

      <!-- 浏览模式：网格分页 -->
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
          <a-empty
            :description="
              album.canManage ? '专辑还没有图片，去「我的图片」里加入吧' : '这个专辑还没有公开作品'
            "
          />
        </div>
        <a-spin v-else :spinning="fetching">
          <PictureWaterfall :pictures="pictures" @open="openPicture" />
          <div v-if="loadError" class="page-error">
            <span class="load-more-error">{{ loadError }}</span>
            <a-button size="small" type="primary" ghost @click="retryLoad">重试</a-button>
          </div>
          <div class="pagination-bar" data-testid="album-pagination">
            <a-pagination
              :current="currentPage"
              :page-size="pageSize"
              :total="total"
              :page-size-options="PAGE_SIZE_OPTIONS"
              show-size-changer
              :show-total="(t: number) => `共 ${t} 张`"
              @change="handlePageChange"
            />
          </div>
        </a-spin>
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
              <img
                class="manage-cover"
                :src="getThumbnailUrl(record.url, 160)"
                :alt="record.name"
              />
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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
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

const DEFAULT_PAGE_SIZE = 16
const PAGE_SIZE_OPTIONS = ['8', '12', '16', '20']

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
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const fetching = ref(false)
const initialLoading = ref(true)
const loadError = ref('')

// 管理模式：单独做服务端分页，避免和浏览模式的分页状态互相干扰
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

const fetchPage = async (page = currentPage.value, resetState = false) => {
  const seq = (requestSeq += 1)
  fetching.value = true
  loadError.value = ''
  if (resetState) {
    pictures.value = []
    total.value = 0
    initialLoading.value = true
  }
  try {
    const res = await listAlbumPicturePage({
      albumId: props.id,
      current: page,
      pageSize: pageSize.value,
    })
    if (seq !== requestSeq) {
      return
    }
    if (res.data.code === 0 && res.data.data) {
      pictures.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
      currentPage.value = page
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

const handlePageChange = (page: number, nextPageSize: number) => {
  const sizeChanged = nextPageSize !== pageSize.value
  pageSize.value = nextPageSize
  currentPage.value = sizeChanged ? 1 : page
  window.scrollTo({ top: 0, behavior: 'smooth' })
  fetchPage(currentPage.value)
}

const retryLoad = () => {
  fetchPage(currentPage.value, pictures.value.length === 0)
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

// 切到管理模式时按需加载；切回浏览模式保持原有分页
watch(mode, (value) => {
  if (value === 'manage' && album.value.canManage) {
    manageCurrent.value = 1
    fetchManagePictures()
  }
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
      await fetchPage(1, true)
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
    fetchPage(1, true)
  } else {
    initialLoading.value = false
  }
})
</script>

<style scoped>
#albumDetailPage {
  max-width: 1240px;
  margin: 0 auto;
}

.album-header {
  margin-bottom: 26px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.album-header :deep(.ant-card-body) {
  padding: 24px;
}

.album-header-main {
  display: flex;
  gap: 24px;
  align-items: center;
}

.album-cover {
  flex: 0 0 184px;
  width: 184px;
  height: 138px;
  overflow: hidden;
  background: var(--surface-subtle);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.album-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.album-cover-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--ink-400);
  font-size: 13px;
}

.album-info {
  flex: 1;
  min-width: 0;
}

.album-title {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.album-title h2 {
  margin: 0;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 24px;
  letter-spacing: -0.02em;
}

.album-desc {
  margin-top: 8px;
  color: var(--ink-500);
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.album-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
  color: var(--ink-400);
  font-size: 13px;
}

.album-actions {
  margin-top: 16px;
}

.manage-cover {
  display: block;
  width: 72px;
  height: 54px;
  object-fit: cover;
  border: 1px solid var(--line);
  border-radius: 6px;
}

.sub-text {
  color: var(--ink-400);
  font-size: 12px;
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 72px 0;
}

.page-error {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  margin-top: 16px;
}

.load-more-error {
  color: var(--danger);
  font-size: 13px;
}

.pagination-bar {
  display: flex;
  justify-content: center;
  margin-top: 28px;
  padding: 16px 20px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
}

@media (max-width: 680px) {
  .album-header-main {
    flex-direction: column;
    align-items: flex-start;
  }

  .album-cover {
    width: 100%;
    height: auto;
    aspect-ratio: 4 / 3;
  }
}
</style>
