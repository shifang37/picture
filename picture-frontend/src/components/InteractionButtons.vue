<template>
  <a-space :size="8">
    <a-button
      :type="state.favorite ? 'primary' : 'default'"
      :loading="loading.favorite"
      data-testid="favorite-button"
      @click="toggle('favorite')"
    >
      <template #icon>
        <StarFilled v-if="state.favorite" />
        <StarOutlined v-else />
      </template>
      {{ state.favorite ? '已收藏' : '收藏' }} {{ formatCount(state.favoriteCount) }}
    </a-button>
    <a-button
      :type="state.like ? 'primary' : 'default'"
      :ghost="!state.like"
      :loading="loading.like"
      data-testid="like-button"
      @click="toggle('like')"
    >
      <template #icon>
        <HeartFilled v-if="state.like" />
        <HeartOutlined v-else />
      </template>
      点赞 {{ formatCount(state.likeCount) }}
    </a-button>
  </a-space>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { HeartFilled, HeartOutlined, StarFilled, StarOutlined } from '@ant-design/icons-vue'
import {
  addInteraction,
  getInteractionStatus,
  removeInteraction,
  type InteractionType,
} from '@/api/interactionExtra'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import {
  applyOptimisticToggle,
  formatCount,
  mergeServerStatus,
  toCount,
  type InteractionKind,
  type InteractionState,
} from '@/utils/interaction'

const props = defineProps<{
  pictureId: string
  favoriteCount?: string | number
  likeCount?: string | number
}>()

const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()

const state = ref<InteractionState>({
  favorite: false,
  like: false,
  favoriteCount: toCount(props.favoriteCount),
  likeCount: toCount(props.likeCount),
})

const loading = reactive({ favorite: false, like: false })

// 连点保护：同一按钮 300ms 内的第二次点击直接忽略，避免产生大量请求
const lastClickAt = reactive<Record<InteractionKind, number>>({ favorite: 0, like: 0 })

const toType = (kind: InteractionKind): InteractionType => kind

const fetchStatus = async () => {
  try {
    const res = await getInteractionStatus(props.pictureId)
    if (res.data.code === 0) {
      state.value = mergeServerStatus(state.value, res.data.data)
    }
  } catch {
    // 状态查询失败时保持「未选中 + 服务端计数」，用户点击仍可正常发起互动
  }
}

const requireLogin = () => {
  if (loginUserStore.loginUser.id) {
    return true
  }
  message.warning('请先登录')
  router.push(`/user/login?redirect=${encodeURIComponent(route.fullPath)}`)
  return false
}

const toggle = async (kind: InteractionKind) => {
  if (!requireLogin()) {
    return
  }
  const now = Date.now()
  if (now - lastClickAt[kind] < 300) {
    return
  }
  lastClickAt[kind] = now

  // 乐观更新：先改本地，失败时用快照回滚
  const snapshot = { ...state.value }
  state.value = applyOptimisticToggle(state.value, kind)
  const shouldAdd = kind === 'favorite' ? state.value.favorite : state.value.like
  loading[kind] = true
  try {
    const res = shouldAdd
      ? await addInteraction({ pictureId: props.pictureId, type: toType(kind) })
      : await removeInteraction({ pictureId: props.pictureId, type: toType(kind) })
    if (res.data.code === 0) {
      state.value = mergeServerStatus(state.value, res.data.data)
    } else {
      state.value = snapshot
      message.error(res.data.message || '操作失败，请稍后重试')
    }
  } catch (error: any) {
    state.value = snapshot
    message.error(error?.message || '网络异常，请稍后重试')
  } finally {
    loading[kind] = false
  }
}

onMounted(() => {
  fetchStatus()
})
</script>
