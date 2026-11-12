<template>
  <a-modal
    :open="props.open"
    title="批量编辑"
    :confirm-loading="submitting"
    ok-text="保存"
    cancel-text="取消"
    width="560px"
    @ok="handleSubmit"
    @cancel="close"
  >
    <a-alert
      type="info"
      show-icon
      message="只会更新你填写的字段"
      description="普通用户修改已过审图片的内容后，图片会重新进入待审核；内容没有变化的图片不受影响。"
      style="margin-bottom: 16px"
    />

    <a-form layout="vertical">
      <a-form-item label="分类">
        <a-select
          v-model:value="form.category"
          allow-clear
          placeholder="不修改分类"
          :options="categoryOptions"
        />
      </a-form-item>
      <a-form-item label="标签">
        <a-select
          v-model:value="form.tags"
          mode="tags"
          allow-clear
          placeholder="不修改标签；删除全部标签表示清空"
          :options="tagOptions"
          :max-tag-count="6"
          @change="handleTagsChange"
        />
      </a-form-item>
      <a-form-item label="简介">
        <a-textarea
          v-model:value="form.introduction"
          :rows="3"
          :maxlength="512"
          show-count
          placeholder="不修改简介"
        />
      </a-form-item>
    </a-form>

    <div class="selected-tip">已选择 {{ props.ids.length }} 张图片</div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { listPictureTagCategory } from '@/api/pictureController'
import { batchEditPictures, type PictureBatchResult } from '@/api/pictureExtra'
import { PICTURE_TAG_MAX_COUNT } from '@/constants/picture'
import { buildBatchEditPayload } from '@/utils/batchEdit'

const props = defineProps<{
  open: boolean
  ids: string[]
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'success', result: PictureBatchResult): void
}>()

const form = reactive<{
  category?: string
  tags?: string[]
  introduction: string
}>({
  category: undefined,
  tags: undefined,
  introduction: '',
})

const submitting = ref(false)
// 标签需要区分「没动过」和「清空」：没动过不提交，清空要提交空数组
const tagsTouched = ref(false)
const categoryList = ref<string[]>([])
const tagList = ref<string[]>([])
const optionsLoaded = ref(false)

const categoryOptions = computed(() =>
  categoryList.value.map((category) => ({ label: category, value: category })),
)
const tagOptions = computed(() => tagList.value.map((tag) => ({ label: tag, value: tag })))

const resetForm = () => {
  form.category = undefined
  form.tags = undefined
  form.introduction = ''
  tagsTouched.value = false
}

// 每次打开都重置表单，避免把上一次的编辑带到下一批
watch(
  () => props.open,
  (open) => {
    if (open) {
      resetForm()
      loadOptions()
    }
  },
)

const loadOptions = async () => {
  if (optionsLoaded.value) {
    return
  }
  try {
    const res = await listPictureTagCategory()
    if (res.data.code === 0 && res.data.data) {
      categoryList.value = res.data.data.categoryList ?? []
      tagList.value = res.data.data.tagList ?? []
      optionsLoaded.value = true
    }
  } catch {
    // 字典加载失败不阻塞编辑：仍可手工输入标签
  }
}

const handleTagsChange = () => {
  tagsTouched.value = true
}

const close = () => {
  emit('update:open', false)
}

const handleSubmit = async () => {
  if (!props.ids.length) {
    message.warning('请先选择要编辑的图片')
    return
  }
  const payload = buildBatchEditPayload({
    category: form.category,
    tags: form.tags,
    tagsTouched: tagsTouched.value,
    introduction: form.introduction,
  })
  if (!payload.hasChanges) {
    message.warning('请至少填写一个要更新的字段')
    return
  }
  // 标签数量按后端上限提前拦截，避免整批请求被拒绝
  if (payload.tags && payload.tags.length > PICTURE_TAG_MAX_COUNT) {
    message.warning(`标签最多 ${PICTURE_TAG_MAX_COUNT} 个`)
    return
  }
  submitting.value = true
  try {
    const res = await batchEditPictures({
      ids: props.ids,
      category: payload.category,
      tags: payload.tags,
      introduction: payload.introduction,
    })
    if (res.data.code === 0 && res.data.data) {
      const result = res.data.data
      if (result.failCount) {
        message.warning(`更新 ${result.successCount ?? 0} 张，${result.errors?.join('；')}`)
      } else if (!result.successCount) {
        message.info('所选图片内容没有变化，无需更新')
      } else {
        message.success(`已更新 ${result.successCount} 张图片`)
      }
      emit('success', result)
      close()
    } else {
      message.error('批量编辑失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('批量编辑失败：' + (error?.message || '系统错误'))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.selected-tip {
  color: #999;
  font-size: 12px;
  text-align: right;
}
</style>
