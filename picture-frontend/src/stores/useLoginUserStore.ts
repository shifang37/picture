import { getLoginUser } from '@/api/userController'
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<API.LoginUserVO>({
    userName: '未登录',
  })

  async function fetchLoginUser() {
    try {
      const res = await getLoginUser()
      if (res.data.code === 0 && res.data.data) {
        loginUser.value = res.data.data
      }
    } catch (error) {
      // 后端接口暂未实现，忽略错误
      console.log('获取登录用户信息失败，后端接口可能未实现')
    }
  }

  function setLoginUser(newLoginUser: any) {
    loginUser.value = newLoginUser
  }

  return { loginUser, setLoginUser, fetchLoginUser }
})
