<template>
  <a-card
    hoverable
    :bordered="false"
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
        <span v-if="picture.category" class="cover-category">{{ picture.category }}</span>
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
    <a-card-meta>
      <template #title>
        <!-- 搜索命中时高亮名称：逐段文本插值，不使用 v-html -->
        <span class="picture-name" data-testid="picture-name">
          <template v-for="(segment, index) in nameSegments" :key="index">
            <mark v-if="segment.matched" class="picture-name-hit">{{ segment.text }}</mark>
            <template v-else>{{ segment.text }}</template>
          </template>
        </span>
      </template>
      <template #description>
        <div class="picture-tags">
          <a-tag v-for="tag in picture.tags" :key="tag" class="picture-tag">
            {{ tag }}
          </a-tag>
        </div>
        <!-- 互动计数：热度排序时用户能直观看到排序依据 -->
        <div class="picture-stats">
          <span><StarOutlined /> {{ formatCount(picture.favoriteCount) }}</span>
          <span><HeartOutlined /> {{ formatCount(picture.likeCount) }}</span>
          <span><DownloadOutlined /> {{ formatCount(picture.downloadCount) }}</span>
        </div>
      </template>
    </a-card-meta>
  </a-card>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { DownloadOutlined, HeartOutlined, StarOutlined } from '@ant-design/icons-vue'
import { getThumbnailUrl } from '@/utils'
import { highlightSegments } from '@/utils/highlight'
import { formatCount } from '@/utils/interaction'
import { getPictureAspectRatio } from '@/utils/waterfall'
import InteractionButtons from '@/components/InteractionButtons.vue'

const props = withDefaults(
  defineProps<{
    picture: API.PictureVO
    /** 当前列宽对应的缩略图尺寸，由瀑布流根据容器宽度计算 */
    thumbnailSize?: number
    /** 当前生效的搜索词，用于名称高亮；为空时不做高亮 */
    keyword?: string
  }>(),
  { thumbnailSize: 400, keyword: '' },
)

const emit = defineEmits<{
  (event: 'open', picture: API.PictureVO): void
}>()

const aspectRatio = computed(() => getPictureAspectRatio(props.picture))

const nameSegments = computed(() =>
  highlightSegments(props.picture.name || '未命名', props.keyword),
)
</script>

<style scoped>
.picture-card {
  overflow: hidden;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 2px 10px -6px rgba(15, 23, 42, 0.28);
  cursor: pointer;
  transition:
    transform 0.25s cubic-bezier(0.2, 0.7, 0.2, 1),
    box-shadow 0.25s ease;
}

.picture-card:hover {
  box-shadow: 0 26px 50px -30px rgba(15, 23, 42, 0.55);
  transform: translateY(-4px);
}

.picture-card :deep(.ant-card-cover) {
  margin: 0;
}

.picture-card :deep(.ant-card-body) {
  padding: 12px 14px 14px;
}

.picture-cover {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: linear-gradient(135deg, #eef2ff 0%, #f8fafc 100%);
}

.picture-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.5s cubic-bezier(0.2, 0.7, 0.2, 1);
}

.picture-card:hover .picture-cover img {
  transform: scale(1.04);
}

.cover-category {
  position: absolute;
  bottom: 12px;
  left: 12px;
  padding: 3px 10px;
  color: #fff;
  font-size: 11.5px;
  letter-spacing: 0.02em;
  background: rgba(15, 23, 42, 0.52);
  border-radius: 999px;
  opacity: 0;
  transform: translateY(6px);
  transition:
    opacity 0.25s ease,
    transform 0.25s ease;
  backdrop-filter: blur(8px);
}

.picture-card:hover .cover-category {
  opacity: 1;
  transform: none;
}

.interaction-overlay {
  position: absolute;
  top: 10px;
  right: 10px;
  padding: 4px;
  background: rgba(15, 23, 42, 0.5);
  border-radius: 999px;
  opacity: 0;
  transition: opacity 0.2s ease;
  backdrop-filter: blur(8px);
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

.picture-name {
  color: var(--ink-900);
  font-weight: 600;
  font-size: 14px;
  letter-spacing: -0.01em;
}

.picture-name-hit {
  padding: 0 1px;
  color: inherit;
  background: rgba(250, 204, 21, 0.42);
  border-radius: 4px;
}

.picture-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.picture-tag {
  margin: 0;
  padding-inline: 8px;
  color: var(--ink-500);
  font-size: 11px;
  line-height: 20px;
  background: #f6f7fb;
  border-color: transparent;
}

.picture-stats {
  display: flex;
  gap: 14px;
  color: var(--ink-400);
  font-size: 12px;
}

.picture-stats span {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
</style>
