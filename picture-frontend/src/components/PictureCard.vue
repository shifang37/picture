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
      <div class="picture-cover">
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
        <div v-if="picture.tags?.length" class="picture-tags">
          <a-tag v-for="tag in visibleTags" :key="tag" class="picture-tag">
            {{ tag }}
          </a-tag>
          <span v-if="extraTagCount > 0" class="picture-tag-more">+{{ extraTagCount }}</span>
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

const nameSegments = computed(() =>
  highlightSegments(props.picture.name || '未命名', props.keyword),
)

const visibleTags = computed(() => (props.picture.tags ?? []).slice(0, 3))
const extraTagCount = computed(() => Math.max(0, (props.picture.tags?.length ?? 0) - 3))
</script>

<style scoped>
.picture-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  transition:
    transform 0.28s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.28s ease,
    border-color 0.22s ease;
}

.picture-card:hover {
  border-color: var(--line-strong);
  box-shadow: var(--shadow-md);
  transform: translateY(-4px);
}

.picture-card :deep(.ant-card-cover) {
  margin: 0;
}

.picture-card :deep(.ant-card-body) {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  padding: 13px 15px 14px;
}

.picture-card :deep(.ant-card-meta) {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.picture-card :deep(.ant-card-meta-detail) {
  display: flex;
  flex: 1;
  flex-direction: column;
}

.picture-card :deep(.ant-card-meta-title) {
  margin-bottom: 8px !important;
}

.picture-card :deep(.ant-card-meta-description) {
  display: flex;
  flex: 1;
  flex-direction: column;
}

/* 统一 4:3 封面：所有卡片等高，网格每行自动对齐 */
.picture-cover {
  position: relative;
  width: 100%;
  overflow: hidden;
  aspect-ratio: 4 / 3;
  background: linear-gradient(135deg, #f3f4f6 0%, #eaeae6 100%);
}

.picture-cover::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    to top,
    rgba(24, 24, 27, 0.38) 0%,
    rgba(24, 24, 27, 0) 42%,
    rgba(24, 24, 27, 0.16) 100%
  );
  opacity: 0;
  transition: opacity 0.28s ease;
  content: '';
  pointer-events: none;
}

.picture-card:hover .picture-cover::after {
  opacity: 1;
}

.picture-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.55s cubic-bezier(0.22, 1, 0.36, 1);
}

.picture-card:hover .picture-cover img {
  transform: scale(1.045);
}

.cover-category {
  position: absolute;
  bottom: 10px;
  left: 10px;
  z-index: 2;
  padding: 3px 10px;
  color: #fff;
  font-weight: 500;
  font-size: 11.5px;
  letter-spacing: 0.02em;
  background: rgba(24, 24, 27, 0.62);
  border: 1px solid rgba(255, 255, 255, 0.16);
  border-radius: 999px;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.interaction-overlay {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 2;
  padding: 4px;
  background: rgba(24, 24, 27, 0.62);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 999px;
  opacity: 0;
  transform: translateY(-3px);
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

/* 悬浮或键盘聚焦时露出互动按钮；触屏设备没有 hover，始终可见 */
.picture-card:hover .interaction-overlay,
.picture-card:focus-within .interaction-overlay {
  opacity: 1;
  transform: none;
}

@media (hover: none) {
  .interaction-overlay {
    opacity: 1;
    transform: none;
  }
}

.picture-name {
  display: block;
  overflow: hidden;
  color: var(--ink-900);
  font-weight: 600;
  font-size: 14px;
  letter-spacing: -0.01em;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.picture-name-hit {
  padding: 0 3px;
  color: var(--ink-900);
  background: #fef08a;
  border-radius: 4px;
}

.picture-tags {
  display: flex;
  flex-wrap: nowrap;
  gap: 6px;
  align-items: center;
  margin-bottom: 10px;
  overflow: hidden;
}

.picture-tag {
  margin: 0;
  padding-inline: 8px;
  color: var(--ink-500);
  font-size: 11.5px;
  line-height: 20px;
  background: var(--surface-subtle);
  border-color: transparent;
}

.picture-tag-more {
  flex: none;
  color: var(--ink-400);
  font-size: 11px;
}

.picture-stats {
  display: flex;
  gap: 14px;
  margin-top: auto;
  padding-top: 8px;
  color: var(--ink-400);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  border-top: 1px solid var(--line);
}

.picture-stats span {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}
</style>
