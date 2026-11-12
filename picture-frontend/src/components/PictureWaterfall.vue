<template>
  <div ref="listRef" class="waterfall" data-testid="picture-waterfall">
    <div v-for="(column, columnIndex) in columns" :key="columnIndex" class="waterfall-column">
      <PictureCard
        v-for="picture in column"
        :key="picture.id"
        :picture="picture"
        :thumbnail-size="thumbnailSize"
        @open="emit('open', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 通用图片瀑布流：按图片宽高比分配列，图片加载前就占好高度，避免布局跳动。
 * 首页、作者主页、专辑详情共用同一套列分配与缩略图尺寸计算逻辑。
 */
import { computed, ref, watch } from 'vue'
import { useDevicePixelRatio, useElementSize } from '@vueuse/core'
import PictureCard from '@/components/PictureCard.vue'
import {
  distributeToColumns,
  getColumnCount,
  getColumnWidth,
  getPictureAspectRatio,
  getThumbnailSize,
} from '@/utils/waterfall'

const props = defineProps<{
  pictures: API.PictureVO[]
}>()

const emit = defineEmits<{
  (event: 'open', picture: API.PictureVO): void
}>()

const listRef = ref<HTMLElement | null>(null)
const { width: containerWidth } = useElementSize(listRef)
const { pixelRatio } = useDevicePixelRatio()
// 页面隐藏或 keep-alive 移出 DOM 时宽度会变成 0，记住最后一次有效宽度
const lastContainerWidth = ref(0)
watch(containerWidth, (value) => {
  if (value > 0) {
    lastContainerWidth.value = value
  }
})
const effectiveWidth = computed(() => lastContainerWidth.value || 1200)
const columnCount = computed(() => getColumnCount(effectiveWidth.value))
const thumbnailSize = computed(() =>
  getThumbnailSize(getColumnWidth(effectiveWidth.value, columnCount.value), pixelRatio.value),
)
const columns = computed(() =>
  distributeToColumns(props.pictures, columnCount.value, getPictureAspectRatio),
)
</script>

<style scoped>
.waterfall {
  display: flex;
  align-items: flex-start;
  gap: 16px;
}

.waterfall-column {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}
</style>
