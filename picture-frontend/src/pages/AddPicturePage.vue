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
        <a-button type="primary" html-type="submit" style="width: 100%">
          {{ isEdit ? '保存修改' : '创建' }}
        </a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import PictureUpload from '@/components/PictureUpload.vue'
import { editPicture, listPictureTagCategory, getPictureVoById } from '@/api/pictureController'

const router = useRouter()
const route = useRoute()

// 是否是编辑已有图片
const isEdit = computed(() => typeof route.query?.id === 'string')

const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})

// 标签逐个校验长度，避免总长度或单个标签过长被后端拒绝
const validateTags = (_rule: any, value?: string[]) => {
  if (value?.some((tag) => tag.length > 32)) {
    return Promise.reject('单个标签不能超过 32 个字符')
  }
  return Promise.resolve()
}

// 表单校验规则：与后端 DTO / 数据库字段长度保持一致
const formRules = {
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

const onSuccess = (newPicture: API.PictureVO) => {
  const isFirstUpload = !picture.value?.id
  picture.value = newPicture
  // 首次上传时用文件名作为默认名称；重新上传时不要覆盖用户已经填写的内容
  if (isFirstUpload || !pictureForm.name) {
    pictureForm.name = newPicture.name
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
    message.error('保存失败，' + (res.data.message || '未知错误'))
  }
}

const categoryOptions = ref<{ value: string; label: string }[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])

// 获取标签和分类选项
const getTagCategoryOptions = async () => {
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
    message.error('加载选项失败，' + res.data.message)
  }
}

// 获取老数据
const getOldPicture = async () => {
  // 获取数据
  const id = route.query?.id
  // 雪花 ID 超出 JS 安全整数范围，必须按字符串传递，不能转 Number
  if (typeof id === 'string') {
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
  }
}

onMounted(() => {
  getTagCategoryOptions()
  getOldPicture()
})
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}
</style>
