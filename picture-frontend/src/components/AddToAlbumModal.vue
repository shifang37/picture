<template>
  <a-modal
    :open="props.open"
    title="加入专辑"
    :confirm-loading="submitting"
    ok-text="加入"
    cancel-text="取消"
    width="480px"
    @ok="handleSubmit"
    @cancel="close"
  >
    <a-spin :spinning="loading">
      <a-empty
        v-if="!albums.length && !loading"
        description="还没有专辑，先去「我的专辑」创建一个吧"
      />
      <template v-else>
        <div class="tip">已选择 {{ props.pictureIds.length }} 张图片，重复加入会自动跳过。</div>
        <a-select
          v-model:value="selectedAlbumId"
          class="album-select"
          placeholder="选择要加入的专辑"
          :options="albumOptions"
          data-testid="album-select"
        />
      </template>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { addAlbumPictures, listMyAlbumPage, type AlbumVO } from '@/api/albumExtra'

const props = defineProps<{
  open: boolean
  pictureIds: string[]
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'success'): void
}>()

const loading = ref(false)
const submitting = ref(false)
const albums = ref<AlbumVO[]>([])
const selectedAlbumId = ref<string | undefined>(undefined)

const albumOptions = computed(() =>
  albums.value.map((album) => ({
    label: `${album.name || '未命名专辑'}（${Number(album.pictureCount) || 0} 张）`,
    value: String(album.id),
  })),
)

const loadAlbums = async () => {
  loading.value = true
  try {
    // 公开分页上限 20，这里只需要让用户选择最近的专辑
    const res = await listMyAlbumPage({ current: 1, pageSize: 20 })
    if (res.data.code === 0 && res.data.data) {
      albums.value = res.data.data.records ?? []
    } else {
      message.error('加载专辑失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('加载专辑失败：' + (error?.message || '系统错误'))
  } finally {
    loading.value = false
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      selectedAlbumId.value = undefined
      loadAlbums()
    }
  },
)

const close = () => {
  emit('update:open', false)
}

const handleSubmit = async () => {
  if (!selectedAlbumId.value) {
    message.warning('请选择要加入的专辑')
    return
  }
  if (!props.pictureIds.length) {
    message.warning('请先选择图片')
    return
  }
  submitting.value = true
  try {
    const res = await addAlbumPictures(selectedAlbumId.value, props.pictureIds)
    if (res.data.code === 0) {
      message.success('已加入专辑')
      emit('success')
      close()
    } else {
      message.error('加入专辑失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('加入专辑失败：' + (error?.message || '系统错误'))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tip {
  margin-bottom: 12px;
  color: #999;
  font-size: 13px;
}

.album-select {
  width: 100%;
}
</style>
