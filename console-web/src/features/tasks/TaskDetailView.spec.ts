import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const taskDetailFixture = {
  id: 't1',
  issueRef: 'engineering/defect-intake#12',
  issue: {
    title: 'NullPointer in intake',
    url: 'https://gitlab.example/engineering/defect-intake/-/issues/12',
    attachments: [{ name: 'stack.txt', url: 'https://gitlab.example/uploads/stack.txt' }],
  },
  completeness: {
    decision: '缺失字段判断',
    missingFields: ['复现步骤'],
  },
  profile: {
    revision: 3,
    baseSha: 'abc123def456',
  },
  scheduling: {
    reason: '调度理由',
    chosenScore: 0.91,
    candidates: [
      { nodeId: 'n1', score: 0.91, rejectionReason: null },
      { nodeId: 'n2', score: 0.4, rejectionReason: '并发已满' },
    ],
  },
  attempt: {
    attemptId: 'a1',
    pipelineId: 'p1',
    jobId: 'j1',
    executor: 'opencode',
  },
  commands: [
    {
      program: 'mvn',
      args: ['-B', 'test'],
      display: 'mvn -B test',
      exitCode: 0,
      log: 'Tests run: 1',
    },
  ],
  patch: {
    summary: 'Guard null intake payload',
    changedFiles: ['src/Intake.java'],
  },
  artifacts: [{ name: 'result.tgz', sha256: 'deadbeef' }],
  mergeRequest: {
    url: 'https://gitlab.example/engineering/defect-intake/-/merge_requests/9',
    branch: 'repair/t1',
    commitSha: 'cafebabe',
  },
  stateTransitions: [{ from: 'QUEUED', to: 'RUNNING', at: '2026-07-20T00:00:00Z' }],
  auditOperations: [{ action: 'schedule', actor: 'system', at: '2026-07-20T00:00:01Z' }],
  events: [],
}

async function mountWithRouter(component: object, path: string) {
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/tasks/:taskId', name: 'task-detail', component },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(component, {
    global: {
      plugins: [router],
    },
  })
}

class FakeEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  constructor(public readonly url: string) {}
  close() {}
}

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.includes('/api/tasks/t1') && !url.includes('/events')) {
        return new Response(JSON.stringify(taskDetailFixture), {
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

it('shows triage, scheduling, agent, validation and publication evidence', async () => {
  const { default: TaskDetailView } = await import('./TaskDetailView.vue')
  const wrapper = await mountWithRouter(TaskDetailView, '/tasks/t1')
  await flushPromises()
  expect(wrapper.text()).toContain('engineering/defect-intake#12')
  expect(wrapper.text()).toContain('缺失字段判断')
  expect(wrapper.text()).toContain('调度理由')
  expect(wrapper.text()).toContain('mvn -B test')
  expect(wrapper.get('[data-test="merge-request"]').attributes('href')).toBe(
    taskDetailFixture.mergeRequest.url,
  )
})
