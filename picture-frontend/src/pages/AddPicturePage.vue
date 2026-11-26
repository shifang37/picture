<template>
  <div id="addPicturePage">
    <div class="gallery-page-head">
      <div>
        <h2 class="gallery-page-title">
          {{ isEdit ? '修改图片' : '创建图片' }}
        </h2>
        <div class="gallery-page-desc">
          {{
            isEdit
              ? '更新图片元数据或重新上传文件，保存后立即生效'
              : '上传图片后可自动获取 AI 建议，一键填充名称、简介、分类与标签'
          }}
        </div>
      </div>
      <a-button v-if="!isEdit" @click="router.push('/batch_upload')">批量上传 →</a-button>
    </div>

    <a-card class="upload-panel" :bordered="false">
      <PictureUpload :picture="picture" :onSuccess="onSuccess" />
    </a-card>

    <a-alert
      v-if="picture && picture.reviewStatus !== 1"
      :type="picture.reviewStatus === 2 ? 'error' : 'warning'"
      show-icon
      :message="picture.reviewStatus === 2 ? '图片未通过审核' : '图片已上传，等待管理员审核'"
      :description="reviewDescription"
      class="panel-gap"
    />

    <a-alert
      v-if="showReReviewHint"
      type="warning"
      show-icon
      message="修改后需要重新审核"
      description="修改名称、简介、分类或标签后，图片会重新进入待审核，审核通过前不会出现在首页。内容没有变化则不会触发重新审核。"
      class="panel-gap"
    />

    <a-card v-if="aiSuggestion" size="small" class="ai-card panel-gap">
      <template #title>
        <span class="ai-card-title">AI 智能标注建议</span>
      </template>
      <template #extra>
        <a-button type="primary" size="small" @click="applyAiSuggestion">一键填入</a-button>
      </template>
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
    </a-card>

    <a-card v-if="picture" class="form-panel panel-gap" :bordered="false" title="图片信息">
      <a-form layout="vertical" :model="pictureForm" :rules="formRules" @finish="handleSubmit">
        <a-form-item label="名称" name="name">
          <a-input v-model:value="pictureForm.name" placeholder="请输入名称" allowClear />
        </a-form-item>
        <a-form-item label="简介" name="introduction">
          <a-textarea
            v-model:value="pictureForm.introduction"
            placeholder="请输入简介"
            :rows="3"
            autoSize
            allowClear
          />
        </a-form-item>
        <a-row :gutter="16">
          <a-col :xs="24" :sm="12">
            <a-form-item label="分类" name="category">
              <a-auto-complete
                v-model:value="pictureForm.category"
                :options="categoryOptions"
                placeholder="请选择或输入分类"
                allowClear
              />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :sm="12">
            <a-form-item label="标签" name="tags">
              <a-select
                v-model:value="pictureForm.tags"
                :options="tagOptions"
                mode="tags"
                placeholder="请输入或选择标签"
                allowClear
              />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item class="submit-row">
          <a-button type="primary" size="large" html-type="submit" block :loading="submitting">
            {{ isEdit ? '保存修改' : '发布图片' }}
          </a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message, type FormProps } from 'ant-design-vue'
import PictureUpload from '@/components/PictureUpload.vue'
import { PICTURE_TAG_MAX_COUNT, PICTURE_TAG_MAX_LENGTH } from '@/constants/picture'
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

// 标签逐个校验长度，避免单个标签过长被后端拒绝
const validateTags = (_rule: any, value?: string[]) => {
  if (value?.some((tag) => tag.length > PICTURE_TAG_MAX_LENGTH)) {
    return Promise.reject(`单个标签不能超过 ${PICTURE_TAG_MAX_LENGTH} 个字符`)
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
    {
      type: 'array',
      max: PICTURE_TAG_MAX_COUNT,
      message: `标签最多 ${PICTURE_TAG_MAX_COUNT} 个`,
      trigger: 'change',
    },
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
  max-width: 780px;
  margin: 0 auto;
}

.upload-panel,
.form-panel {
  border: 1px solid var(--line);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-xs);
}

.panel-gap {
  margin-top: 18px;
}

.ai-card {
  background: linear-gradient(135deg, rgba(37, 99, 235, 0.04) 0%, #ffffff 60%);
  border: 1px solid rgba(37, 99, 235, 0.18);
  border-radius: var(--radius-lg);
}

.ai-card-title {
  color: var(--brand-600);
  font-weight: 600;
}

.ai-suggestion-item {
  display: flex;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--ink-700);
  line-height: 1.65;
}

.ai-suggestion-item:last-child {
  margin-bottom: 0;
}

.ai-suggestion-label {
  flex: 0 0 42px;
  color: var(--ink-400);
  font-size: 13px;
}

.submit-row {
  margin-top: 8px;
  margin-bottom: 0;
}
</style>
