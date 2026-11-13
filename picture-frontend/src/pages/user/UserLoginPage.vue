<template>
  <div id="userLoginPage" class="auth-page">
    <div class="auth-card">
      <aside class="auth-aside">
        <div class="aside-brand">
          <span class="aside-brand-mark">shifang37</span>
          <span class="aside-brand-sub">云图库</span>
        </div>
        <h2 class="aside-title">把喜欢的画面，收进自己的图库</h2>
        <p class="aside-desc">登录后可以上传图片、整理专辑、收藏灵感，并在任意设备上继续浏览。</p>
        <ul class="aside-points">
          <li>标签与分类检索</li>
          <li>回收站与存储配额</li>
          <li>一键生成分享卡片</li>
        </ul>
        <p class="aside-foot">shifang37 · 图片素材管理与分享</p>
      </aside>
      <section class="auth-main">
        <h2 class="title">欢迎回来</h2>
        <p class="desc">使用账号密码登录 shifang37云图库</p>
        <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
          <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
            <a-input v-model:value="formState.userAccount" placeholder="账号" />
          </a-form-item>
          <a-form-item
            name="userPassword"
            :rules="[
              { required: true, message: '请输入密码' },
              { min: 6, message: '密码不能小于 6 位' },
            ]"
          >
            <a-input-password v-model:value="formState.userPassword" placeholder="密码" />
          </a-form-item>
          <div class="tips">
            没有账号？
            <RouterLink to="/user/register">去注册</RouterLink>
          </div>
          <a-form-item>
            <a-button type="primary" html-type="submit" class="submit-btn" :loading="loading">
              登录
            </a-button>
          </a-form-item>
        </a-form>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { userLogin } from '@/api/userController'
import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { message } from 'ant-design-vue'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})
const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()
const loading = ref(false)
const handleSubmit = async (values: any) => {
  loading.value = true
  try {
    const res = await userLogin(values)
    if (res.data.code == 0 && res.data.data) {
      // 直接设置登录用户信息
      loginUserStore.setLoginUser(res.data.data)
      // 尝试获取完整的用户信息
      await loginUserStore.fetchLoginUser()
      // 首次登录 / 管理员重置密码后必须先改密，改密完成前不放行到其他页面
      if (res.data.data.passwordChangeRequired) {
        message.warning('首次登录请先修改初始密码')
        await router.replace('/user/changePassword')
        return
      }
      message.success('登录成功')
      // 登录成功后回到被拦截的页面（只接受站内路径）
      const redirect = route.query.redirect
      const target =
        typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
          ? redirect
          : '/'
      router.replace(target)
    } else {
      message.error('登录失败：' + (res.data.message || '未知错误'))
    }
  } catch (e: any) {
    message.error('登录失败：' + (e.message || '系统错误'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  display: grid;
  min-height: calc(100vh - 220px);
  padding: 24px 0;
  place-items: center;
}

.auth-card {
  display: grid;
  grid-template-columns: 1.02fr 1fr;
  width: min(920px, 100%);
  overflow: hidden;
  background: #fff;
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 28px;
  box-shadow: var(--shadow-lg);
}

.auth-aside {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 46px 42px;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(150deg, #312e81 0%, #4f46e5 48%, #9333ea 100%);
}

.auth-aside::after {
  position: absolute;
  top: -80px;
  right: -60px;
  width: 260px;
  height: 260px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.28), transparent 65%);
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.aside-brand {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 40px;
}

.aside-brand-mark {
  font-weight: 700;
  font-size: 18px;
  letter-spacing: -0.02em;
}

.aside-brand-sub {
  color: rgba(255, 255, 255, 0.66);
  font-size: 11px;
  letter-spacing: 0.26em;
}

.aside-title {
  margin: 0 0 14px;
  font-weight: 700;
  font-size: 26px;
  line-height: 1.38;
  letter-spacing: -0.02em;
}

.aside-desc {
  margin: 0;
  color: rgba(255, 255, 255, 0.76);
  font-size: 13.5px;
  line-height: 1.85;
}

.aside-points {
  display: grid;
  gap: 10px;
  margin: 30px 0 0;
  padding: 0;
  list-style: none;
}

.aside-points li {
  display: flex;
  gap: 10px;
  align-items: center;
  color: rgba(255, 255, 255, 0.9);
  font-size: 13px;
}

.aside-points li::before {
  width: 16px;
  height: 16px;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  content: '✓';
  background: rgba(255, 255, 255, 0.18);
  border-radius: 50%;
}

.aside-foot {
  margin: auto 0 0;
  padding-top: 36px;
  color: rgba(255, 255, 255, 0.55);
  font-size: 11.5px;
  letter-spacing: 0.08em;
}

.auth-main {
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 46px 44px;
}

.title {
  margin: 0 0 6px;
  color: var(--ink-900);
  font-weight: 700;
  font-size: 24px;
  letter-spacing: -0.02em;
}

.desc {
  margin: 0 0 28px;
  color: var(--ink-400);
  font-size: 13.5px;
}

.tips {
  margin: 2px 0 18px;
  color: var(--ink-400);
  font-size: 13px;
  text-align: right;
}

.submit-btn {
  width: 100%;
  height: 44px;
  border-radius: 12px;
  font-weight: 600;
}

@media (max-width: 860px) {
  .auth-page {
    min-height: auto;
  }

  .auth-card {
    grid-template-columns: 1fr;
  }

  .auth-aside {
    display: none;
  }

  .auth-main {
    padding: 34px 24px;
  }
}
</style>
