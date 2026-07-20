import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { fetchTaskDetail, taskEventsUrl } from '../../api/http'
import type { TaskDetail, TaskEvent } from '../../api/types'

const TERMINAL_TYPES = new Set([
  'attempt.succeeded',
  'attempt.failed',
  'attempt.cancelled',
  'task.ready-for-test',
  'task.failed',
  'task.cancelled',
])

export function useTaskEvents(taskId: Ref<string>, detail: Ref<TaskDetail | null>) {
  const events = ref<TaskEvent[]>([])
  const seenIds = new Set<string>()
  let source: EventSource | null = null
  let reconnectDelayMs = 1000
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let disposed = false

  function clearReconnect() {
    if (reconnectTimer != null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function closeSource() {
    if (source != null) {
      source.onmessage = null
      source.onerror = null
      source.close()
      source = null
    }
  }

  function appendEvent(event: TaskEvent) {
    if (!event.id || seenIds.has(event.id)) {
      return
    }
    seenIds.add(event.id)
    events.value = [...events.value, event]
    const terminal = event.terminal === true || TERMINAL_TYPES.has(event.type)
    if (terminal) {
      void refreshDetail()
    }
  }

  async function refreshDetail() {
    if (!taskId.value) {
      return
    }
    try {
      detail.value = await fetchTaskDetail(taskId.value)
    } catch {
      // keep last known detail on transient errors
    }
  }

  function connect() {
    closeSource()
    if (!taskId.value || disposed) {
      return
    }
    const url = taskEventsUrl(taskId.value)
    source = new EventSource(url, { withCredentials: true })
    source.onmessage = (message) => {
      reconnectDelayMs = 1000
      let payload: Record<string, unknown> = {}
      try {
        payload = message.data ? (JSON.parse(message.data) as Record<string, unknown>) : {}
      } catch {
        payload = { message: String(message.data ?? '') }
      }
      appendEvent({
        id: message.lastEventId || String(payload.id ?? `${Date.now()}`),
        type: String(payload.type ?? payload.eventType ?? 'event'),
        message: String(payload.message ?? payload.summary ?? message.data ?? ''),
        at: payload.at as string | undefined,
        terminal: Boolean(payload.terminal),
      })
    }
    source.onerror = () => {
      closeSource()
      if (disposed) {
        return
      }
      clearReconnect()
      reconnectTimer = setTimeout(() => {
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000)
        connect()
      }, reconnectDelayMs)
    }
  }

  watch(
    taskId,
    (id) => {
      events.value = []
      seenIds.clear()
      reconnectDelayMs = 1000
      clearReconnect()
      closeSource()
      if (id) {
        connect()
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    disposed = true
    clearReconnect()
    closeSource()
  })

  return {
    events,
    refreshDetail,
  }
}
