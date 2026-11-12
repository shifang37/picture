<template>
  <a-card
    hoverable
    class="picture-card"
    data-testid="picture-card"
    :aria-label="picture.name || '图片卡片'"
    @click="emit('open', picture)"
  >
    <template #cover>
      <div class="picture-cover" :style="{ aspectRatio: `1 / ${aspectRatio}` }">
        <img
          :alt="picture.name || '图片'"
          :src="getThumbnailUrl(picture.url, thumbnailSize)"
          loading="lazy"
          decoding="async"
        />
        <!-- 悬浮显示互动入口：点击按钮不触发卡片跳转（@click.stop 在外层与按钮上各兜一层） -->
        <div class="interaction-overlay" @click.stop>
          <InteractionButtons
            compact
            :picture-id="String(picture.id)"
            :favorite-count="picture.favoriteCount"
            :like-count="picture.likeCount"
          />
        </div>
      </div>
    </template>
    <a-card-meta :title="picture.name || '未命名'">
      <template #description>
        <div class="picture-tags">
          <a-tag color="green">
            {{ picture.category ?? '默认' }}
          </a-tag>
          <a-tag v-for="tag in picture.tags" :key="tag">
            {{ tag }}
          </a-tag>
        </div>
        <!-- 互动计数：热度排序时用户能直观看到排序依据 -->
        <div class="picture-stats">
          <span>收藏 {{ formatCount(picture.favoriteCount) }}</span>
          <span>点赞 {{ formatCount(picture.likeCount) }}</span>
          <span>下载 {{ formatCount(picture.downloadCount) }}</span>
        </div>
      </template>
    </a-card-meta>
  </a-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { getThumbnailUrl } from '@/utils'
import { formatCount } from '@/utils/interaction'
import { getPictureAspectRatio } from '@/utils/waterfall'
import InteractionButtons from '@/components/InteractionButtons.vue'

const props = withDefaults(
  defineProps<{
    picture: API.PictureVO
    /** 当前列宽对应的缩略图尺寸，由瀑布流根据容器宽度计算 */
    thumbnailSize?: number
  }>(),
  { thumbnailSize: 400 },
)

const emit = defineEmits<{
  (event: 'open', picture: API.PictureVO): void
}>()

const aspectRatio = computed(() => getPictureAspectRatio(props.picture))
</script>

<style scoped>
.picture-card {
  cursor: pointer;
}

.picture-cover {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: #f2f2f2;
}

.picture-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.interaction-overlay {
  position: absolute;
  top: 8px;
  right: 8px;
  padding: 4px;
  border-radius: 8px;
  background: rgba(0, 0, 0, 0.45);
  opacity: 0;
  transition: opacity 0.2s ease;
}

/* 悬浮或键盘聚焦时露出互动按钮；触屏设备没有 hover，始终可见 */
.picture-card:hover .interaction-overlay,
.picture-card:focus-within .interaction-overlay {
  opacity: 1;
}

@media (hover: none) {
  .interaction-overlay {
    opacity: 1;
  }
}

.picture-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.picture-stats {
  display: flex;
  gap: 12px;
  margin-top: 4px;
  color: #999;
  font-size: 12px;
}
</style>
