<template>
  <div id="myAlbumsPage">
    <div class="page-header">
      <div>
        <h2>我的专辑</h2>
        <div class="desc">把作品按主题分组；公开专辑会展示在作者主页，只展示已过审图片</div>
      </div>
      <a-button type="primary" @click="openCreate">创建专辑</a-button>
    </div>

    <div v-if="loading" class="state-block" data-testid="albums-loading">
      <a-spin size="large" />
    </div>

    <div v-else-if="loadError" class="state-block">
      <a-empty :description="loadError">
        <a-button type="primary" @click="fetchAlbums">重试</a-button>
      </a-empty>
    </div>

    <div v-else-if="!albums.length" class="state-block" data-testid="albums-empty">
      <a-empty description="还没有专辑，创建一个来整理作品吧">
        <a-button type="primary" @click="openCreate">创建专辑</a-button>
      </a-empty>
    </div>

    <template v-else>
      <a-row :gutter="[16, 16]">
        <a-col v-for="album in albums" :key="album.id" :xs="24" :sm="12" :md="8" :lg="6">
          <a-card hoverable class="album-card" data-testid="album-card" @click="openAlbum(album)">
            <template #cover>
              <div class="album-cover">
                <img
                  v-if="album.coverUrl"
                  :src="getThumbnailUrl(album.coverUrl, 400)"
                  :alt="album.name || '专辑封面'"
                  loading="lazy"
                />
                <div v-else class="album-cover-empty">暂无封面</div>
                <a-tag class="album-visibility" :color="album.isPublic === 1 ? 'green' : 'default'">
                  {{ album.isPublic === 1 ? '公开' : '私密' }}
                </a-tag>
              </div>
            </template>
            <a-card-meta :title="album.name || '未命名专辑'">
              <template #description>
                <div class="album-desc">{{ album.description || '暂无简介' }}</div>
                <div class="album-stats">
                  公开 {{ formatCount(album.publicCount) }} · 共
                  {{ formatCount(album.pictureCount) }}
                </div>
              </template>
            </a-card-meta>
            <template #actions>
              <span @click.stop="openEdit(album)"><EditOutlined /> 编辑</span>
              <a-popconfirm
                title="确定删除这个专辑吗？专辑内的图片不会被删除。"
                ok-text="删除"
                cancel-text="取消"
                @confirm="doDelete(album)"
              >
                <span class="delete-action" data-testid="album-delete" @click.stop>
                  <DeleteOutlined /> 删除
                </span>
              </a-popconfirm>
            </template>
          </a-card>
        </a-col>
      </a-row>

      <div class="pagination">
        <a-pagination
          :current="query.current"
          :page-size="query.pageSize"
          :total="total"
          :show-size-changer="false"
          @change="handlePageChange"
        />
      </div>
    </template>

    <a-modal
      :open="editorOpen"
      :title="editingId ? '编辑专辑' : '创建专辑'"
      :confirm-loading="saving"
      ok-text="保存"
      cancel-text="取消"
      @ok="handleSave"
      @cancel="editorOpen = false"
    >
      <a-form layout="vertical">
        <a-form-item label="专辑名称" required>
          <a-input
            v-model:value="form.name"
            :maxlength="64"
            placeholder="给专辑起个名字"
            data-testid="album-name-input"
          />
        </a-form-item>
        <a-form-item label="简介">
          <a-textarea
            v-model:value="form.description"
            :rows="3"
            :maxlength="512"
            show-count
            placeholder="简单介绍一下这个专辑"
          />
        </a-form-item>
        <a-form-item label="公开">
          <a-switch
            v-model:checked="form.isPublic"
            checked-children="公开"
            un-checked-children="私密"
          />
          <div class="form-tip">
            公开后其他人可以在你的作者主页看到该专辑；仅已过审图片会被展示。
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { DeleteOutlined, EditOutlined } from '@ant-design/icons-vue'
import { addAlbum, deleteAlbum, listMyAlbumPage, updateAlbum, type AlbumVO } from '@/api/albumExtra'
import { formatCount } from '@/utils/interaction'
import { getThumbnailUrl } from '@/utils'

const router = useRouter()

const albums = ref<AlbumVO[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const query = reactive({ current: 1, pageSize: 12 })

const editorOpen = ref(false)
const editingId = ref<string | undefined>(undefined)
const saving = ref(false)
const form = reactive({ name: '', description: '', isPublic: false })

const fetchAlbums = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await listMyAlbumPage({ current: query.current, pageSize: query.pageSize })
    if (res.data.code === 0 && res.data.data) {
      albums.value = res.data.data.records ?? []
      total.value = Number(res.data.data.total) || 0
    } else {
      loadError.value = res.data.message || '加载专辑失败'
    }
  } catch (error: any) {
    loadError.value = error?.message || '网络异常，请稍后重试'
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page: number) => {
  query.current = page
  fetchAlbums()
}

const resetForm = () => {
  form.name = ''
  form.description = ''
  form.isPublic = false
}

const openCreate = () => {
  editingId.value = undefined
  resetForm()
  editorOpen.value = true
}

const openEdit = (album: AlbumVO) => {
  editingId.value = album.id
  form.name = album.name ?? ''
  form.description = album.description ?? ''
  form.isPublic = album.isPublic === 1
  editorOpen.value = true
}

const handleSave = async () => {
  const name = form.name.trim()
  if (!name) {
    message.warning('专辑名称不能为空')
    return
  }
  saving.value = true
  try {
    const res = editingId.value
      ? await updateAlbum({
          id: editingId.value,
          name,
          description: form.description.trim(),
          isPublic: form.isPublic ? 1 : 0,
        })
      : await addAlbum({
          name,
          description: form.description.trim() || undefined,
          isPublic: form.isPublic,
        })
    if (res.data.code === 0) {
      message.success(editingId.value ? '专辑已更新' : '专辑已创建')
      editorOpen.value = false
      // 编辑当前页的最后一项时不会出问题；新建后回到第一页看到最新专辑
      if (!editingId.value) {
        query.current = 1
      }
      await fetchAlbums()
    } else {
      message.error('保存失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('保存失败：' + (error?.message || '系统错误'))
  } finally {
    saving.value = false
  }
}

const doDelete = async (album: AlbumVO) => {
  if (!album.id) {
    return
  }
  try {
    const res = await deleteAlbum(album.id)
    if (res.data.code === 0) {
      message.success('专辑已删除，图片本身不受影响')
      if (albums.value.length === 1 && query.current > 1) {
        query.current -= 1
      }
      await fetchAlbums()
    } else {
      message.error('删除失败：' + (res.data.message || '未知错误'))
    }
  } catch (error: any) {
    message.error('删除失败：' + (error?.message || '系统错误'))
  }
}

const openAlbum = (album: AlbumVO) => {
  if (album.id) {
    router.push(`/album/${album.id}`)
  }
}

onMounted(() => {
  fetchAlbums()
})
</script>

<style scoped>
#myAlbumsPage {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h2 {
  margin-bottom: 4px;
}

.desc {
  color: #999;
  font-size: 13px;
}

.album-card {
  cursor: pointer;
}

.album-cover {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  background: #f2f2f2;
}

.album-cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.album-cover-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #bbb;
  font-size: 13px;
}

.album-visibility {
  position: absolute;
  top: 8px;
  right: 8px;
}

.album-desc {
  min-height: 40px;
  overflow: hidden;
  color: #666;
  font-size: 13px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.album-stats {
  margin-top: 6px;
  color: #999;
  font-size: 12px;
}

.delete-action {
  color: #ff4d4f;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

.state-block {
  display: flex;
  justify-content: center;
  padding: 64px 0;
}

.form-tip {
  margin-top: 6px;
  color: #999;
  font-size: 12px;
}
</style>
