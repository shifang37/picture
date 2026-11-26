<template>
  <div id="authorPage">
    <!-- 加载中 -->
    <div v-if="loading" class="state-block" data-testid="author-loading">
      <a-spin size="large" />
    </div>

    <!-- 作者不存在 / 已删除 -->
    <a-result
      v-else-if="notFound"
      status="404"
      title="作者不存在"
      sub-title="该用户可能已被删除，或者链接有误"
      data-testid="author-not-found"
    >
      <template #extra>
        <a-button type="primary" @click="goHome">返回首页</a-button>
      </template>
    </a-result>

    <template v-else>
      <!-- 作者资料卡 -->
      <a-card class="profile-card" :bordered="false" data-testid="author-profile">
        <div class="profile">
          <a-avatar
            :size="72"
            :src="profile?.user?.userAvatar || undefined"
            data-testid="author-avatar"
          >
            {{ (profile?.user?.userName || '无').slice(0, 1) }}
          </a-avatar>
          <div class="profile-main">
            <div class="profile-name">
              <span data-testid="author-name">{{ profile?.user?.userName || '无名' }}</span>
              <a-button v-if="isSelf" type="link" size="small" @click="goProfile">
                编辑资料
              </a-button>
            </div>
            <div class="profile-desc">
              {{ profile?.user?.userProfile || '这个人很懒，还没有写简介' }}
            </div>
            <div class="profile-meta">加入于 {{ formatJoinedAt(profile?.joinedAt) }}</div>
          </div>
        </div>

        <a-row class="stats" :gutter="[16, 16]">
          <a-col :xs="12" :sm="6">
            <div class="stat">
              <div class="stat-value" data-testid="author-picture-count">
                {{ formatCount(profile?.pictureCount) }}
              </div>
              <div class="stat-label">公开作品</div>
            </div>
          </a-col>
          <a-col :xs="12" :sm="6">
            <div class="stat">
              <div class="stat-value">{{ formatCount(profile?.downloadCount) }}</div>
              <div class="stat-label">总下载</div>
            </div>
          </a-col>
          <a-col :xs="12" :sm="6">
            <div class="stat">
              <div class="stat-value">{{ formatCount(profile?.favoriteCount) }}</div>
              <div class="stat-label">总收藏</div>
            </div>
          </a-col>
          <a-col :xs="12" :sm="6">
            <div class="stat">
              <div class="stat-value">{{ formatCount(profile?.likeCount) }}</div>
              <div class="stat-label">总点赞</div>
            </div>
          </a-col>
        </a-row>
      </a-card>

      <div class="section-header">
        <h3>TA 的作品</h3>
        <span v-if="total" class="section-total">共 {{ total }} 张</span>
      </div>

      <!-- 作品首屏加载 -->
      <div v-if="initialLoading" class="state-block" data-testid="author-pictures-loading">
        <a-spin />
      </div>

      <!-- 作品加载失败 -->
      <div v-else-if="!pictures.length && loadError" class="state-block">
        <a-empty description="作品加载失败，请稍后重试">
          <a-button type="primary" @click="retryLoad">重试</a-button>
        </a-empty>
      </div>

      <!-- 空状态 -->
      <div v-else-if="!pictures.length" class="state-block" data-testid="author-empty">
        <a-empty description="TA 还没有公开作品" />
      </div>

      <a-spin v-else :spinning="fetching">
        <PictureWaterfall :pictures="pictures" @open="openPicture" />

        <div v-if="loadError" class="page-error">
          <span class="load-more-error">{{ loadError }}</span>
          <a-button size="small" type="primary" ghost @click="retryLoad">重试</a-button>
        </div>

        <div class="pagination-bar" data-testid="author-pagination">
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
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { getPublicUserProfile, type PublicUserProfile } from '@/api/userExtra'
import { listPictureVoByPage } from '@/api/pictureController'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { formatCount } from '@/utils/interaction'
import PictureWaterfall from '@/components/PictureWaterfall.vue'

// 与公开列表接口保持一致：每页最多 20 条
const DEFAULT_PAGE_SIZE = 16
const PAGE_SIZE_OPTIONS = ['8', '12', '16', '20']

const props = defineProps<{
  id: string
}>()

const router = useRouter()
const loginUserStore = useLoginUserStore()

const loading = ref(true)
const notFound = ref(false)
const profile = ref<PublicUserProfile | null>(null)

const pictures = ref<API.PictureVO[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const fetching = ref(false)
const initialLoading = ref(true)
const loadError = ref('')

const isSelf = computed(() => {
  const loginUserId = loginUserStore.loginUser.id
  return !!loginUserId && String(loginUserId) === String(props.id)
})

const formatJoinedAt = (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD') : '-')

const goHome = () => {
  router.push('/')
}

const goProfile = () => {
  router.push('/user/profile')
}

const openPicture = (picture: API.PictureVO) => {
  if (picture.id) {
    router.push(`/picture/${picture.id}`)
  }
}

const fetchProfile = async () => {
  loading.value = true
  notFound.value = false
  try {
    const res = await getPublicUserProfile(props.id)
    if (res.data.code === 0 && res.data.data) {
      profile.value = res.data.data
    } else if (res.data.code === 40400) {
      notFound.value = true
    } else {
      notFound.value = true
    }
  } catch {
    // 网络异常也按「无法访问该主页」处理，避免出现半截页面
    notFound.value = true
  } finally {
    loading.value = false
  }
}

// 请求序号：作者切换或翻页时丢弃过期响应
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
    const res = await listPictureVoByPage({
      current: page,
      pageSize: pageSize.value,
      userId: props.id,
      sortField: 'createTime',
      sortOrder: 'descend',
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

onMounted(async () => {
  await fetchProfile()
  // 作者不存在时不再请求作品列表，避免无意义的 404 请求
  if (!notFound.value) {
    fetchPage(1, true)
  } else {
    initialLoading.value = false
  }
})
</script>

<style scoped>
#authorPage {
  max-width: 1240px;
  margin: 0 auto;
}

.profile-card {
  margin-bottom: 28px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.profile-card :deep(.ant-card-body) {
  padding: 28px;
}

.profile {
  display: flex;
  gap: 22px;
  align-items: center;
}

.profile :deep(.ant-avatar) {
  flex: none;
  color: #fff;
  font-weight: 700;
  font-size: 26px;
  background: var(--brand);
  box-shadow: var(--shadow-sm);
}

.profile-main {
  flex: 1;
  min-width: 0;
}

.profile-name {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 24px;
  letter-spacing: -0.02em;
}

.profile-desc {
  margin-top: 8px;
  color: var(--ink-500);
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.profile-meta {
  margin-top: 8px;
  color: var(--ink-400);
  font-size: 12.5px;
}

.stats {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--line);
}

.stat {
  padding: 14px 16px;
  text-align: center;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
}

.stat-value {
  color: var(--ink-900);
  font-weight: 700;
  font-size: 24px;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.stat-label {
  margin-top: 4px;
  color: var(--ink-400);
  font-size: 12.5px;
}

.section-header {
  display: flex;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 18px;
}

.section-header h3 {
  margin: 0;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 20px;
  letter-spacing: -0.02em;
}

.section-total {
  color: var(--ink-400);
  font-size: 13px;
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
</style>
