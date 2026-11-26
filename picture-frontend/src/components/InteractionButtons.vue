<template>
  <a-space :size="compact ? 4 : 8" class="interaction-buttons" :class="{ 'is-compact': compact }">
    <a-tooltip :title="state.favorite ? '取消收藏' : '收藏'">
      <a-button
        :size="compact ? 'small' : 'middle'"
        :type="state.favorite ? 'primary' : 'default'"
        :ghost="compact && !state.favorite"
        :loading="loading.favorite"
        :class="['interaction-btn', { 'is-fav-active': state.favorite }]"
        :data-testid="compact ? 'card-favorite-button' : 'favorite-button'"
        :aria-label="state.favorite ? '取消收藏' : '收藏'"
        @click.stop="toggle('favorite')"
      >
        <template #icon>
          <StarFilled v-if="state.favorite" />
          <StarOutlined v-else />
        </template>
        <template v-if="compact">{{ formatCount(state.favoriteCount) }}</template>
        <template v-else>
          {{ state.favorite ? '已收藏' : '收藏' }} {{ formatCount(state.favoriteCount) }}
        </template>
      </a-button>
    </a-tooltip>
    <a-tooltip :title="state.like ? '取消点赞' : '点赞'">
      <a-button
        :size="compact ? 'small' : 'middle'"
        :type="state.like ? 'primary' : 'default'"
        :ghost="compact && !state.like"
        :loading="loading.like"
        :class="['interaction-btn', { 'is-like-active': state.like }]"
        :data-testid="compact ? 'card-like-button' : 'like-button'"
        :aria-label="state.like ? '取消点赞' : '点赞'"
        @click.stop="toggle('like')"
      >
        <template #icon>
          <HeartFilled v-if="state.like" />
          <HeartOutlined v-else />
        </template>
        <template v-if="compact">{{ formatCount(state.likeCount) }}</template>
        <template v-else>点赞 {{ formatCount(state.likeCount) }}</template>
      </a-button>
    </a-tooltip>
  </a-space>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { HeartFilled, HeartOutlined, StarFilled, StarOutlined } from '@ant-design/icons-vue'
import { useInteraction } from '@/composables/useInteraction'
import { formatCount } from '@/utils/interaction'

const props = withDefaults(
  defineProps<{
    pictureId: string
    favoriteCount?: string | number
    likeCount?: string | number
    /** 紧凑模式：只显示图标与计数，用于列表卡片悬浮层 */
    compact?: boolean
  }>(),
  { favoriteCount: undefined, likeCount: undefined, compact: false },
)

const { state, loading, toggle, refresh } = useInteraction({
  pictureId: () => props.pictureId,
  favoriteCount: () => props.favoriteCount,
  likeCount: () => props.likeCount,
})

onMounted(() => {
  refresh()
})
</script>

<style scoped>
.is-compact :deep(.interaction-btn) {
  border-radius: 999px;
  font-size: 12px;
  color: #fff;
  border-color: rgba(255, 255, 255, 0.24);
}

.is-compact :deep(.interaction-btn:hover) {
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
  border-color: rgba(255, 255, 255, 0.45);
}

:deep(.interaction-btn.is-fav-active) {
  background: #d97706 !important;
  border-color: #d97706 !important;
  color: #fff !important;
  box-shadow: none;
}

:deep(.interaction-btn.is-like-active) {
  background: #e11d48 !important;
  border-color: #e11d48 !important;
  color: #fff !important;
  box-shadow: none;
}
</style>
