import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@/access'

import App from './App.vue'
import router from './router'
import 'ant-design-vue/dist/reset.css'

const app = createApp(App)
// Ant Design Vue 组件由 unplugin-vue-components 按需引入，不再全量 app.use(Antd)
app.use(createPinia())
app.use(router)

app.mount('#app')
