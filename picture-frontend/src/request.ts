import axios from 'axios'
import { message } from 'ant-design-vue'

// 创建 Axios 实例
// 说明：后端 JsonConfig 已统一把 Long 序列化为字符串，雪花 ID 不存在精度丢失问题，
// 因此这里不再对响应体做正则改写，直接使用 axios 默认的 JSON 解析。
const myAxios = axios.create({
  baseURL: '/api',
  timeout: 60000,
  withCredentials: true,
})

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      // 不是获取用户信息的请求，并且用户目前不是已经在用户登录页面，则跳转到登录页面
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        window.location.href = `/user/login?redirect=${window.location.href}`
      }
    }
    return response
  },
  function (error) {
    // Any status code that falls outside the range of 2xx cause this function to trigger
    // Do something with response error
    return Promise.reject(error)
  },
)

export default myAxios
