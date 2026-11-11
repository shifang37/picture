<template>
  <div id="tagCategoryManagePage">
    <a-flex justify="space-between" align="center" wrap="wrap" gap="12" style="margin-bottom: 16px">
      <div>
        <h2 style="margin: 0">标签分类管理</h2>
        <div class="page-tip">
          字典维护后，上传页和首页会立即读取新选项；已有图片的标签/分类不受影响。
        </div>
      </div>
      <a-space wrap>
        <a-button :loading="syncLoading" @click="doSync"> 从现有图片同步 </a-button>
        <a-button @click="fetchData">刷新</a-button>
        <a-button type="primary" @click="openAdd">新增{{ typeLabel(activeType) }}</a-button>
      </a-space>
    </a-flex>

    <a-alert
      type="info"
      show-icon
      message="删除字典项不会修改已经使用该标签/分类的图片；如果图片仍在用，它只是不再出现在上传页/首页的选项里。"
      style="margin-bottom: 16px"
    />

    <a-radio-group v-model:value="activeType" button-style="solid" style="margin-bottom: 16px">
      <a-radio-button value="category">分类</a-radio-button>
      <a-radio-button value="tag">标签</a-radio-button>
    </a-radio-group>

    <a-table
      :columns="columns"
      :data-source="filteredList"
      :loading="loading"
      :pagination="false"
      row-key="id"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'type'">
          <a-tag :color="record.type === 'category' ? 'green' : 'blue'">
            {{ typeLabel(record.type) }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-popconfirm
            title="确定删除该字典项吗？"
            ok-text="删除"
            cancel-text="取消"
            @confirm="doDelete(record)"
          >
            <a-button danger type="link">删除</a-button>
          </a-popconfirm>
        </template>
      </template>
    </a-table>

    <a-empty
      v-if="!loading && filteredList.length === 0"
      :description="`暂无${typeLabel(activeType)}，可以手动新增或从现有图片同步`"
      style="margin-top: 24px"
    />

    <a-modal
      v-model:open="modalOpen"
      :title="`新增${typeLabel(form.type)}`"
      :confirm-loading="submitting"
      ok-text="确认新增"
      cancel-text="取消"
      destroy-on-close
      @ok="handleAdd"
    >
      <a-form layout="vertical">
        <a-form-item label="类型">
          <a-radio-group v-model:value="form.type">
            <a-radio value="category">分类</a-radio>
            <a-radio value="tag">标签</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item :label="`${typeLabel(form.type)}名称`">
          <a-input
            v-model:value="form.name"
            :placeholder="`请输入${typeLabel(form.type)}名称（最多 64 个字符）`"
            :maxlength="64"
            show-count
            allow-clear
            @press-enter="handleAdd"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  addTagCategory,
  deleteTagCategory,
  listTagCategoryAdmin,
  syncTagCategory,
  type TagCategoryItem,
  type TagCategoryType,
} from '@/api/tagCategoryExtra'

const columns = [
  { title: '名称', dataIndex: 'name' },
  { title: '类型', dataIndex: 'type', width: 120 },
  { title: '创建时间', dataIndex: 'createTime', width: 200 },
  { title: '操作', key: 'action', width: 120 },
]

const activeType = ref<TagCategoryType>('category')
const dataList = ref<TagCategoryItem[]>([])
const loading = ref(false)
const syncLoading = ref(false)

const filteredList = computed(() => dataList.value.filter((item) => item.type === activeType.value))

const typeLabel = (type?: string) => (type === 'tag' ? '标签' : '分类')

const formatTime = (value?: string) => (value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-')

const fetchData = async () => {
  loading.value = true
  try {
    const res = await listTagCategoryAdmin()
    if (res.data.code === 0) {
      dataList.value = res.data.data ?? []
    } else {
      message.error('获取标签分类失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('获取标签分类失败：' + (e.message || '系统错误'))
  } finally {
    loading.value = false
  }
}

// ==================== 新增 ====================

const modalOpen = ref(false)
const submitting = ref(false)
const form = reactive<{ type: TagCategoryType; name: string }>({
  type: 'category',
  name: '',
})

const openAdd = () => {
  form.type = activeType.value
  form.name = ''
  modalOpen.value = true
}

const handleAdd = async () => {
  const name = form.name.trim()
  if (!name) {
    message.warning(`请输入${typeLabel(form.type)}名称`)
    return
  }
  if (name.length > 64) {
    message.warning('名称不能超过 64 个字符')
    return
  }
  submitting.value = true
  try {
    const res = await addTagCategory({ type: form.type, name })
    if (res.data.code === 0) {
      message.success(`新增${typeLabel(form.type)}成功`)
      activeType.value = form.type
      modalOpen.value = false
      fetchData()
    } else {
      message.error('新增失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('新增失败：' + (e.message || '系统错误'))
  } finally {
    submitting.value = false
  }
}

// ==================== 删除 ====================

const doDelete = async (record: TagCategoryItem) => {
  if (!record.id) {
    return
  }
  try {
    const res = await deleteTagCategory(record.id)
    if (res.data.code === 0) {
      message.success('删除成功')
      fetchData()
    } else {
      message.error('删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('删除失败：' + (e.message || '系统错误'))
  }
}

// ==================== 从现有图片同步 ====================

const doSync = async () => {
  syncLoading.value = true
  try {
    const res = await syncTagCategory()
    if (res.data.code === 0) {
      message.success(`同步完成，新增 ${res.data.data ?? 0} 条标签/分类`)
      fetchData()
    } else {
      message.error('同步失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('同步失败：' + (e.message || '系统错误'))
  } finally {
    syncLoading.value = false
  }
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#tagCategoryManagePage {
  max-width: 1000px;
  margin: 0 auto;
}

.page-tip {
  color: #888;
  font-size: 13px;
  margin-top: 4px;
}
</style>
