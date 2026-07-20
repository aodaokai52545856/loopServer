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

const flatTaskDetail = {
  id: 't2',
  projectKey: 'backend-a',
  state: 'RUNNING',
  priority: 1,
  createdAt: '2026-07-20T00:00:00Z',
  updatedAt: '2026-07-20T00:00:01Z',
  defectId: 'd2',
  defectRevision: 1,
  profileRevision: 2,
  baseSha: 'abc123',
  profileSnapshotJson: '{}',
  issueIid: 7,
  issueUrl: 'https://gitlab.example/engineering/backend-a/-/issues/7',
  title: 'Flat T02 shape',
  description: 'no nested completeness',
  defectState: 'repair::running',
  missingFieldsJson: '[]',
}

async function mountWithRouter(component: object, path: string) {
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/tasks/:taskId', name: 'task-detail', component }],
  })
  await router.push(path)
  await router.isReady()
  return mount(component, {
    global: {
      plugins: [router],
    },
  })
}

function sseResponse(frames: string): Response {
  return new Response(frames, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/api/tasks/t1/events')) {
        return sseResponse(
          [
            'id: a1:3',
            'event: agent.started',
            'data: {"attemptId":"a1","seq":3,"type":"agent.started","eventTime":"2026-07-20T00:00:02Z","payloadJson":"{}"}',
            '',
            '',
          ].join('\n'),
        )
      }
      if (url.includes('/api/tasks/t1') && !url.includes('/events')) {
        return new Response(JSON.stringify(taskDetailFixture), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      if (url.includes('/api/tasks/t2/events')) {
        const headers = new Headers(init?.headers)
        const lastEventId = headers.get('Last-Event-ID')
        if (lastEventId === 'a2:1') {
          return sseResponse(
            [
              'id: a2:2',
              'event: test.finished',
              'data: {"attemptId":"a2","seq":2,"type":"test.finished","eventTime":"2026-07-20T00:00:03Z","payloadJson":"{\\"ok\\":true}"}',
              '',
              '',
            ].join('\n'),
          )
        }
        return sseResponse(
          [
            'id: a2:1',
            'event: agent.started',
            'data: {"attemptId":"a2","seq":1,"type":"agent.started","eventTime":"2026-07-20T00:00:02Z","payloadJson":"{}"}',
            '',
            '',
          ].join('\n'),
        )
      }
      if (url.includes('/api/tasks/t2') && !url.includes('/events')) {
        return new Response(JSON.stringify(flatTaskDetail), {
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

it('renders named SSE events on the timeline', async () => {
  const { default: TaskDetailView } = await import('./TaskDetailView.vue')
  const wrapper = await mountWithRouter(TaskDetailView, '/tasks/t1')
  await flushPromises()
  await vi.waitFor(() => {
    expect(wrapper.text()).toContain('agent.started')
  })
})

it('resumes SSE with Last-Event-ID after the stream ends', async () => {
  const { default: TaskDetailView } = await import('./TaskDetailView.vue')
  const wrapper = await mountWithRouter(TaskDetailView, '/tasks/t2')
  await flushPromises()
  await vi.waitFor(() => {
    expect(wrapper.text()).toContain('agent.started')
  })
  await vi.waitFor(
    () => {
      const calls = vi.mocked(fetch).mock.calls.filter(([input]) => String(input).includes('/events'))
      expect(
        calls.some(([, init]) => new Headers(init?.headers).get('Last-Event-ID') === 'a2:1'),
      ).toBe(true)
      expect(wrapper.text()).toContain('test.finished')
    },
    { timeout: 5000 },
  )
})

it('does not invent triage decision for flat T02 payloads without missing fields', async () => {
  const { default: TaskDetailView } = await import('./TaskDetailView.vue')
  const wrapper = await mountWithRouter(TaskDetailView, '/tasks/t2')
  await flushPromises()
  expect(wrapper.text()).toContain('engineering/backend-a#7')
  expect(wrapper.text()).not.toContain('缺失字段判断')
})
