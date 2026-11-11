<template>
  <div id="addPicturePage">
    <h2 style="margin-bottom: 16px">
      {{ isEdit ? '修改图片' : '创建图片' }}
    </h2>

    <PictureUpload :picture="picture" :onSuccess="onSuccess" />

    <a-alert
      v-if="picture && picture.reviewStatus !== 1"
      :type="picture.reviewStatus === 2 ? 'error' : 'warning'"
      show-icon
      :message="picture.reviewStatus === 2 ? '图片未通过审核' : '图片已上传，等待管理员审核'"
      :description="reviewDescription"
      style="margin: 16px 0"
    />

    <a-alert
      v-if="showReReviewHint"
      type="warning"
      show-icon
      message="修改后需要重新审核"
      description="修改名称、简介、分类或标签后，图片会重新进入待审核，审核通过前不会出现在首页。内容没有变化则不会触发重新审核。"
      style="margin: 16px 0"
    />

    <a-card v-if="aiSuggestion" size="small" title="AI 建议" style="margin: 16px 0">
      <div class="ai-suggestion-item">
        <span class="ai-suggestion-label">名称</span>
        <span>{{ aiSuggestion.name }}</span>
      </div>
      <div v-if="aiSuggestion.introduction" class="ai-suggestion-item">
        <span class="ai-suggestion-label">简介</span>
        <span>{{ aiSuggestion.introduction }}</span>
      </div>
      <div v-if="aiSuggestion.category" class="ai-suggestion-item">
        <span class="ai-suggestion-label">分类</span>
        <span>{{ aiSuggestion.category }}</span>
      </div>
      <div v-if="aiSuggestion.tags?.length" class="ai-suggestion-item">
        <span class="ai-suggestion-label">标签</span>
        <a-space wrap>
          <a-tag v-for="tag in aiSuggestion.tags" :key="tag">{{ tag }}</a-tag>
        </a-space>
      </div>
      <a-button type="primary" ghost size="small" @click="applyAiSuggestion"> 一键填入 </a-button>
    </a-card>

    <a-form
      v-if="picture"
      layout="vertical"
      :model="pictureForm"
      :rules="formRules"
      @finish="handleSubmit"
    >
      <a-form-item label="名称" name="name">
        <a-input v-model:value="pictureForm.name" placeholder="请输入名称" allowClear />
      </a-form-item>
      <a-form-item label="简介" name="introduction">
        <a-textarea
          v-model:value="pictureForm.introduction"
          placeholder="请输入简介"
          :rows="2"
          autoSize
          allowClear
        />
      </a-form-item>
      <a-form-item label="分类" name="category">
        <a-auto-complete
          v-model:value="pictureForm.category"
          :options="categoryOptions"
          placeholder="请选择或输入分类"
          allowClear
        />
      </a-form-item>
      <a-form-item label="标签" name="tags">
        <a-select
          v-model:value="pictureForm.tags"
          :options="tagOptions"
          mode="tags"
          placeholder="请输入或选择标签"
          allowClear
        />
      </a-form-item>

      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%" :loading="submitting">
          {{ isEdit ? '保存修改' : '创建' }}
        </a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message, type FormProps } from 'ant-design-vue'
import PictureUpload from '@/components/PictureUpload.vue'
import { editPicture, listPictureTagCategory, getPictureVoById } from '@/api/pictureController'
import { getPictureAiSuggestion, type PictureAiSuggestion } from '@/api/pictureExtra'
import { useLoginUserStore } from '@/stores/useLoginUserStore'

const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()

// 是否是编辑已有图片
const isEdit = computed(() => typeof route.query?.id === 'string')

const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})
const submitting = ref(false)
const aiSuggestion = ref<PictureAiSuggestion | null>(null)
let aiPollTimer: number | undefined

const AI_POLL_INTERVAL = 1000
const AI_POLL_MAX_ATTEMPTS = 10

const stopAiPolling = () => {
  if (aiPollTimer !== undefined) {
    window.clearTimeout(aiPollTimer)
    aiPollTimer = undefined
  }
}

/**
 * 上传成功后轮询 AI 建议，最多约 10 秒；失败或超时就不显示，不阻断用户手填
 */
const pollAiSuggestion = (pictureId: string) => {
  stopAiPolling()
  aiSuggestion.value = null
  let attempts = 0
  const tick = async () => {
    attempts += 1
    try {
      const res = await getPictureAiSuggestion(pictureId)
      const data = res.data.data
      if (res.data.code === 0 && data) {
        // 1 成功：展示建议；2 失败：停止轮询并保持隐藏
        if (data.status === 1) {
          aiSuggestion.value = data
          return
        }
        if (data.status === 2) {
          return
        }
      }
    } catch {
      // 单次轮询失败不打断用户填写，下一轮继续
    }
    if (attempts < AI_POLL_MAX_ATTEMPTS) {
      aiPollTimer = window.setTimeout(tick, AI_POLL_INTERVAL)
    }
  }
  aiPollTimer = window.setTimeout(tick, 800)
}

const applyAiSuggestion = () => {
  const suggestion = aiSuggestion.value
  if (!suggestion) {
    return
  }
  if (suggestion.name) {
    pictureForm.name = suggestion.name
  }
  if (suggestion.introduction) {
    pictureForm.introduction = suggestion.introduction
  }
  if (suggestion.category) {
    pictureForm.category = suggestion.category
  }
  if (suggestion.tags?.length) {
    pictureForm.tags = [...suggestion.tags]
  }
  message.success('已填入 AI 建议，可继续修改后提交')
}

// 标签逐个校验长度，避免总长度或单个标签过长被后端拒绝
const validateTags = (_rule: any, value?: string[]) => {
  if (value?.some((tag) => tag.length > 32)) {
    return Promise.reject('单个标签不能超过 32 个字符')
  }
  return Promise.resolve()
}

// 表单校验规则：与后端 DTO / 数据库字段长度保持一致
const formRules: FormProps['rules'] = {
  name: [
    { required: true, message: '请输入图片名称', trigger: 'blur' },
    { max: 128, message: '图片名称不能超过 128 个字符', trigger: 'blur' },
  ],
  introduction: [{ max: 512, message: '简介不能超过 512 个字符', trigger: 'blur' }],
  category: [{ max: 64, message: '分类不能超过 64 个字符', trigger: 'blur' }],
  tags: [
    { type: 'array', max: 20, message: '标签最多 20 个', trigger: 'change' },
    { validator: validateTags, trigger: 'change' },
  ],
}

// 未通过审核时给用户的说明
const reviewDescription = computed(() => {
  if (picture.value?.reviewStatus === 2) {
    return picture.value.reviewMessage
      ? `拒绝原因：${picture.value.reviewMessage}。重新上传图片后会再次进入审核。`
      : '该图片未通过审核，重新上传图片后会再次进入审核。'
  }
  return '审核通过后才会出现在首页列表中，审核期间可以在「我的图片」中查看。'
})

// 管理员编辑不会改变审核状态，只有普通用户编辑已通过图片时才提示需要重新审核
const showReReviewHint = computed(
  () =>
    isEdit.value &&
    picture.value?.reviewStatus === 1 &&
    loginUserStore.loginUser.userRole !== 'admin',
)

const onSuccess = (newPicture: API.PictureVO) => {
  const isFirstUpload = !picture.value?.id
  picture.value = newPicture
  // 首次上传时用文件名作为默认名称；重新上传时不要覆盖用户已经填写的内容
  if (isFirstUpload || !pictureForm.name) {
    pictureForm.name = newPicture.name
  }
  // 新上传/重传都重新拉取建议；旧建议不能沿用到新文件
  if (newPicture.id) {
    pollAiSuggestion(newPicture.id)
  }
}

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: any) => {
  const pictureId = picture.value?.id
  if (!pictureId) {
    message.error('请先上传图片')
    return
  }
  submitting.value = true
  try {
    const res = await editPicture({
      id: pictureId,
      ...values,
    })
    if (res.data.code === 0) {
      message.success(isEdit.value ? '保存成功' : '创建成功')
      // 跳转到图片详情页
      router.push({
        path: `/picture/${pictureId}`,
      })
    } else {
      message.error('保存失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('保存失败：' + (e.message || '系统错误'))
  } finally {
    submitting.value = false
  }
}

const categoryOptions = ref<{ value: string; label: string }[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])

// 获取标签和分类选项
const getTagCategoryOptions = async () => {
  try {
    const res = await listPictureTagCategory()
    if (res.data.code === 0 && res.data.data) {
      // 转换成下拉选项组件接受的格式
      tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => {
        return {
          value: data,
          label: data,
        }
      })
      categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => {
        return {
          value: data,
          label: data,
        }
      })
    } else {
      message.error('加载选项失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('加载选项失败：' + (e.message || '系统错误'))
  }
}

// 获取老数据
const getOldPicture = async () => {
  // 获取数据
  const id = route.query?.id
  // 雪花 ID 超出 JS 安全整数范围，必须按字符串传递，不能转 Number
  if (typeof id === 'string') {
    try {
      const res = await getPictureVoById({
        id,
      })
      if (res.data.code === 0 && res.data.data) {
        const data = res.data.data
        picture.value = data
        pictureForm.name = data.name
        pictureForm.introduction = data.introduction
        pictureForm.category = data.category
        pictureForm.tags = data.tags
      } else {
        message.error('获取图片信息失败：' + (res.data.message || '图片不存在'))
      }
    } catch (e: any) {
      message.error('获取图片信息失败：' + (e.message || '系统错误'))
    }
  }
}

onMounted(() => {
  getTagCategoryOptions()
  getOldPicture()
})

onUnmounted(() => {
  stopAiPolling()
})
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}

.ai-suggestion-item {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  line-height: 1.6;
}

.ai-suggestion-label {
  flex: 0 0 40px;
  color: #999;
}
</style>
