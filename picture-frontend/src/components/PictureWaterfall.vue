<template>
  <div ref="listRef" class="waterfall" data-testid="picture-waterfall">
    <PictureCard
      v-for="picture in pictures"
      :key="picture.id"
      :picture="picture"
      :thumbnail-size="thumbnailSize"
      @open="emit('open', $event)"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 通用图片网格：封面统一 4:3，一行高度齐平。
 * 首页、作者主页、专辑详情共用同一套列宽与缩略图尺寸计算逻辑。
 */
import { computed, ref, watch } from 'vue'
import { useDevicePixelRatio, useElementSize } from '@vueuse/core'
import PictureCard from '@/components/PictureCard.vue'
import { getColumnCount, getColumnWidth, getThumbnailSize } from '@/utils/waterfall'

defineProps<{
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
</script>

<style scoped>
.waterfall {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 20px;
  align-items: stretch;
}

@media (max-width: 1120px) {
  .waterfall {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .waterfall {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 520px) {
  .waterfall {
    grid-template-columns: minmax(0, 1fr);
    gap: 14px;
  }
}
</style>
