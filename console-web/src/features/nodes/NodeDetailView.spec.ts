import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const nodeFixture = {
  id: 'n1',
  name: 'dev-mac',
  ownerId: 'Alice',
  description: 'desk node',
  state: 'ONLINE',
  enabled: true,
  concurrencyLimit: 4,
  activeSlots: 1,
  desiredRevision: 7,
  appliedRevision: 7,
  capabilitiesJson: JSON.stringify({
    os: 'macOS',
    arch: 'arm64',
    tools: { opencode: '1.0.0' },
  }),
  allowedProjectsJson: JSON.stringify(['backend-a']),
  lastHeartbeatAt: new Date(Date.now() - 15_000).toISOString(),
  lastError: null,
  recentAttempts: [],
  runnerState: 'online',
  resources: {
    cpuPercent: 12,
    memoryAvailableBytes: 8589934592,
    diskAvailableBytes: 53687091200,
  },
}

async function mountWithRouter(component: object, path: string) {
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/nodes/:nodeId', name: 'node-detail', component }],
  })
  await router.push(path)
  await router.isReady()
  return mount(component, {
    global: {
      plugins: [router],
    },
  })
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/nodes/n1') && !url.includes('/drain')) {
        return new Response(JSON.stringify(nodeFixture), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.includes('/api/session')) {
        return new Response(JSON.stringify({ id: '1', roles: ['ADMIN', 'NODE_OWNER'] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return new Response('not found', { status: 404 })
    }),
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

it('shows ownership, capabilities, slots, revisions and latest health', async () => {
  const { default: NodeDetailView } = await import('./NodeDetailView.vue')
  const wrapper = await mountWithRouter(NodeDetailView, '/nodes/n1')
  await flushPromises()
  for (const text of [
    'Alice',
    'macOS arm64',
    'OpenCode',
    '1 / 4',
    '配置 7 / 已应用 7',
    '15 秒前',
    'CPU 12%',
    '内存',
    '磁盘',
  ]) {
    expect(wrapper.text()).toContain(text)
  }
  expect(wrapper.get('[data-test="cpu"]').text()).toContain('CPU 12%')
  expect(wrapper.get('[data-test="memory"]').text()).toContain('内存')
  expect(wrapper.get('[data-test="disk"]').text()).toContain('磁盘')
})

it('requires confirmation before drain', async () => {
  const { default: NodeDetailView } = await import('./NodeDetailView.vue')
  const wrapper = await mountWithRouter(NodeDetailView, '/nodes/n1')
  await flushPromises()
  await wrapper.get('[data-test="drain"]').trigger('click')
  expect(wrapper.text()).toContain('当前任务完成后停止接单')
})
