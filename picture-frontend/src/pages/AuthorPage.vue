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
          <a-avatar :size="72" :src="profile?.user?.userAvatar" data-testid="author-avatar">
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

      <PictureWaterfall v-else :pictures="pictures" @open="openPicture" />

      <!-- 加载更多 -->
      <div v-if="pictures.length" class="load-more" data-testid="author-load-more">
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
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useInfiniteScroll } from '@vueuse/core'
import dayjs from 'dayjs'
import { getPublicUserProfile, type PublicUserProfile } from '@/api/userExtra'
import { listPictureVoByPage } from '@/api/pictureController'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { formatCount } from '@/utils/interaction'
import PictureWaterfall from '@/components/PictureWaterfall.vue'

// 与公开列表接口保持一致：每页最多 20 条
const PAGE_SIZE = 20

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
const currentPage = ref(0)
const hasMore = ref(false)
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

// 请求序号：作者切换或重试时丢弃过期响应
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
    const res = await listPictureVoByPage({
      current: page,
      pageSize: PAGE_SIZE,
      userId: props.id,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    if (seq !== requestSeq) {
      return
    }
    if (res.data.code === 0 && res.data.data) {
      const records = res.data.data.records ?? []
      const newTotal = Number(res.data.data.total) || 0
      // 分页期间若有新作品过审，后续页会整体后移，按 id 去重
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
      // 先让新卡片渲染完成，再开启下一次滚动加载；否则列表还是旧高度时会被误判为「已到底部」
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

// 滚动到距底部 400px 时自动加载下一页；失败后等用户点重试，避免请求风暴
useInfiniteScroll(typeof window === 'undefined' ? null : window, () => fetchPage(), {
  distance: 400,
  interval: 200,
  canLoadMore: () => hasMore.value && !fetching.value && !loadError.value,
})

onMounted(async () => {
  await fetchProfile()
  // 作者不存在时不再请求作品列表，避免无意义的 404 请求
  if (!notFound.value) {
    fetchPage(true)
  } else {
    initialLoading.value = false
  }
})
</script>

<style scoped>
#authorPage {
  max-width: 1200px;
  margin: 0 auto;
}

.profile-card {
  margin-bottom: 24px;
  background: #fafafa;
}

.profile {
  display: flex;
  align-items: center;
  gap: 20px;
}

.profile-main {
  flex: 1;
  min-width: 0;
}

.profile-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 600;
}

.profile-desc {
  margin-top: 6px;
  color: #666;
  white-space: pre-wrap;
  word-break: break-word;
}

.profile-meta {
  margin-top: 6px;
  color: #999;
  font-size: 12px;
}

.stats {
  margin-top: 20px;
  text-align: center;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
}

.stat-label {
  margin-top: 2px;
  color: #999;
  font-size: 12px;
}

.section-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0;
}

.section-total {
  color: #999;
  font-size: 13px;
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
