<template>
  <div id="userManagePage">
    <a-flex justify="space-between" align="center" wrap="wrap" gap="12" style="margin-bottom: 16px">
      <a-form layout="inline" :model="searchParams" @finish="doSearch">
        <a-form-item label="账号">
          <a-input v-model:value="searchParams.userAccount" placeholder="输入账号" allow-clear />
        </a-form-item>
        <a-form-item label="用户名">
          <a-input v-model:value="searchParams.userName" placeholder="输入用户名" allow-clear />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit">搜索</a-button>
        </a-form-item>
      </a-form>
      <a-button type="primary" @click="openCreate">新增用户</a-button>
    </a-flex>

    <a-table
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      :loading="loading"
      @change="doTableChange"
    >
      <template #headerCell="{ column }">
        <template v-if="column.key === 'userName'">
          <span>
            <smile-outlined />
            用户名
          </span>
        </template>
      </template>

      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'userAvatar'">
          <a-image v-if="record.userAvatar" :src="record.userAvatar" :width="80" />
          <span v-else>-</span>
        </template>
        <template v-else-if="column.dataIndex === 'userProfile'">
          <span :title="record.userProfile">{{ record.userProfile || '-' }}</span>
        </template>
        <template v-else-if="column.dataIndex === 'userRole'">
          <div v-if="record.userRole === 'admin'">
            <a-tag color="green">管理员</a-tag>
          </div>
          <div v-else>
            <a-tag color="blue">普通用户</a-tag>
          </div>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button type="link" @click="openEdit(record)">编辑</a-button>
            <a-popconfirm
              title="确定删除该用户吗？"
              ok-text="删除"
              cancel-text="取消"
              @confirm="doDelete(record.id)"
            >
              <a-button danger type="link">删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="modalOpen"
      :title="isEdit ? '编辑用户' : '新增用户'"
      :confirm-loading="submitting"
      ok-text="保存"
      cancel-text="取消"
      destroy-on-close
      @ok="handleSubmit"
    >
      <a-alert
        v-if="!isEdit"
        type="info"
        show-icon
        :message="`新用户默认密码为 ${DEFAULT_PASSWORD}，请提醒用户登录后及时修改。`"
        style="margin-bottom: 16px"
      />
      <a-form ref="formRef" :model="formState" :rules="formRules" layout="vertical">
        <a-form-item label="账号" name="userAccount">
          <a-input
            v-model:value="formState.userAccount"
            placeholder="请输入账号（4-256 个字符）"
            :maxlength="256"
            :disabled="isEdit"
          />
        </a-form-item>
        <a-form-item label="昵称" name="userName">
          <a-input v-model:value="formState.userName" placeholder="请输入昵称" :maxlength="256" />
        </a-form-item>
        <a-form-item label="头像地址" name="userAvatar">
          <a-input
            v-model:value="formState.userAvatar"
            placeholder="请输入头像图片地址"
            :maxlength="1024"
          />
        </a-form-item>
        <a-form-item label="简介" name="userProfile">
          <a-textarea
            v-model:value="formState.userProfile"
            placeholder="请输入简介"
            :rows="3"
            :maxlength="512"
            show-count
          />
        </a-form-item>
        <a-form-item label="角色" name="userRole">
          <a-select v-model:value="formState.userRole" :options="roleOptions" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message, type FormInstance } from 'ant-design-vue'
import { SmileOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { addUser, deleteUser, listUserVoByPage, updateUser } from '@/api/userController'

const columns = [
  {
    title: 'id',
    dataIndex: 'id',
    width: 180,
  },
  {
    title: '账号',
    dataIndex: 'userAccount',
  },
  {
    title: '用户名',
    dataIndex: 'userName',
    key: 'userName',
  },
  {
    title: '头像',
    dataIndex: 'userAvatar',
    width: 120,
  },
  {
    title: '简介',
    dataIndex: 'userProfile',
  },
  {
    title: '用户角色',
    dataIndex: 'userRole',
    width: 110,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 180,
  },
  {
    title: '操作',
    key: 'action',
    width: 160,
  },
]

const roleOptions = [
  { value: 'user', label: '普通用户' },
  { value: 'admin', label: '管理员' },
]

// 与后端 UserController.DEFAULT_PASSWORD 保持一致
const DEFAULT_PASSWORD = '12345678'

// 数据
const dataList = ref<API.UserVO[]>([])
const total = ref(0)
const loading = ref(false)

// 搜索条件（管理端每页最多 100 条，与后端保持一致）
const searchParams = reactive<API.UserQueryRequest>({
  current: 1,
  pageSize: 10,
})

// 获取数据
const fetchData = async () => {
  loading.value = true
  try {
    const res = await listUserVoByPage({
      ...searchParams,
    })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      message.error('获取数据失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('获取数据失败：' + (e.message || '系统错误'))
  } finally {
    loading.value = false
  }
}

// 分页参数
const pagination = computed(() => {
  return {
    current: searchParams.current ?? 1,
    pageSize: searchParams.pageSize ?? 10,
    total: total.value,
    showSizeChanger: true,
    pageSizeOptions: ['10', '20', '50', '100'],
    showTotal: (total: number) => `共 ${total} 条`,
  }
})

// 表格变化处理
const doTableChange = (page: any) => {
  searchParams.current = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索
const doSearch = () => {
  // 重置页码
  searchParams.current = 1
  fetchData()
}

// ==================== 新增 / 编辑弹窗 ====================

const modalOpen = ref(false)
const submitting = ref(false)
const isEdit = computed(() => Boolean(formState.id))
const formRef = ref<FormInstance>()

const formState = reactive({
  id: undefined as string | undefined,
  userAccount: '',
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: 'user',
})

const formRules = {
  userAccount: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 4, max: 256, message: '账号长度需在 4 到 256 个字符之间', trigger: 'blur' },
  ],
  userName: [{ max: 256, message: '昵称不能超过 256 个字符', trigger: 'blur' }],
  userAvatar: [{ max: 1024, message: '头像地址不能超过 1024 个字符', trigger: 'blur' }],
  userProfile: [{ max: 512, message: '简介不能超过 512 个字符', trigger: 'blur' }],
  userRole: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

const resetForm = () => {
  formState.id = undefined
  formState.userAccount = ''
  formState.userName = ''
  formState.userAvatar = ''
  formState.userProfile = ''
  formState.userRole = 'user'
  formRef.value?.clearValidate()
}

const openCreate = () => {
  resetForm()
  modalOpen.value = true
}

const openEdit = (record: API.UserVO) => {
  formState.id = record.id
  formState.userAccount = record.userAccount ?? ''
  formState.userName = record.userName ?? ''
  formState.userAvatar = record.userAvatar ?? ''
  formState.userProfile = record.userProfile ?? ''
  formState.userRole = record.userRole === 'admin' ? 'admin' : 'user'
  formRef.value?.clearValidate()
  modalOpen.value = true
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const res = isEdit.value
      ? await updateUser({
          id: formState.id,
          userName: formState.userName,
          userAvatar: formState.userAvatar,
          userProfile: formState.userProfile,
          userRole: formState.userRole,
        })
      : await addUser({
          userAccount: formState.userAccount,
          userName: formState.userName,
          userAvatar: formState.userAvatar,
          userProfile: formState.userProfile,
          userRole: formState.userRole,
        })
    if (res.data.code === 0) {
      message.success(isEdit.value ? '保存成功' : `新增成功，默认密码 ${DEFAULT_PASSWORD}`)
      modalOpen.value = false
      if (!isEdit.value) {
        searchParams.current = 1
      }
      fetchData()
    } else {
      message.error('保存失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('保存失败：' + (e.message || '系统错误'))
  } finally {
    submitting.value = false
  }
}

// 删除数据
const doDelete = async (id?: string) => {
  if (!id) {
    return
  }
  try {
    const res = await deleteUser({ id })
    if (res.data.code === 0) {
      message.success('删除成功')
      // 删除后当前页可能为空，回到第一页更稳妥
      if (dataList.value.length <= 1 && (searchParams.current ?? 1) > 1) {
        searchParams.current = (searchParams.current ?? 1) - 1
      }
      fetchData()
    } else {
      message.error('删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('删除失败：' + (e.message || '系统错误'))
  }
}

const formatTime = (time?: string) => (time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-')

// 页面加载时请求一次
onMounted(() => {
  fetchData()
})
</script>
