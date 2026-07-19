import { mount } from '@vue/test-utils'
import DashboardView from './DashboardView.vue'

it('renders the repair dashboard heading', () => {
  const wrapper = mount(DashboardView)
  expect(wrapper.get('h1').text()).toBe('循环工程')
  expect(wrapper.text()).toContain('修复任务')
  expect(wrapper.text()).toContain('节点')
})
