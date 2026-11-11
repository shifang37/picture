import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import NotFoundPage from '@/pages/NotFoundPage.vue'

describe('NotFoundPage', () => {
  it('渲染 404 提示和返回首页按钮', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div>home</div>' } }],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(NotFoundPage, {
      global: {
        plugins: [router],
        stubs: {
          'a-result': {
            template: '<div class="result"><slot name="extra" /></div>',
          },
          'a-space': { template: '<div><slot /></div>' },
          'a-button': { template: '<button><slot /></button>' },
        },
      },
    })

    expect(wrapper.find('.result').exists()).toBe(true)
    expect(wrapper.text()).toContain('返回首页')
    expect(wrapper.text()).toContain('返回上一页')
  })
})
