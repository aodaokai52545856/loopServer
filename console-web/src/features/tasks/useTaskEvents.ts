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

type SseHandlers = {
  onEvent: (type: string, data: string, id: string) => void
}

/**
 * TaskEventStreamController emits named SSE frames (`event: <type>`) and
 * resumes with the Last-Event-ID header. Native EventSource cannot set that
 * header after close()+reconnect, and onmessage ignores non-message types —
 * so the live timeline uses a credentials fetch stream instead.
 */
async function consumeSse(
  url: string,
  lastEventId: string | null,
  signal: AbortSignal,
  handlers: SseHandlers,
): Promise<void> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  if (lastEventId) {
    headers['Last-Event-ID'] = lastEventId
  }

  const response = await fetch(url, {
    method: 'GET',
    credentials: 'same-origin',
    headers,
    signal,
  })
  if (!response.ok) {
    throw new Error(`SSE failed: ${response.status}`)
  }
  if (!response.body) {
    throw new Error('SSE response body missing')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventName = 'message'
  let eventId = ''
  let dataLines: string[] = []

  const flush = () => {
    if (dataLines.length === 0) {
      eventName = 'message'
      eventId = ''
      return
    }
    const data = dataLines.join('\n')
    const type = eventName || 'message'
    const id = eventId
    dataLines = []
    eventName = 'message'
    eventId = ''
    handlers.onEvent(type, data, id)
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) {
      buffer += decoder.decode()
      break
    }
    buffer += decoder.decode(value, { stream: true })
    let newline = buffer.indexOf('\n')
    while (newline >= 0) {
      let line = buffer.slice(0, newline)
      buffer = buffer.slice(newline + 1)
      if (line.endsWith('\r')) {
        line = line.slice(0, -1)
      }
      if (line === '') {
        flush()
      } else if (line.startsWith(':')) {
        // keepalive / comment
      } else if (line.startsWith('event:')) {
        eventName = line.slice(6).trimStart()
      } else if (line.startsWith('id:')) {
        eventId = line.slice(3).trimStart()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
      newline = buffer.indexOf('\n')
    }
  }
  flush()
}

export function useTaskEvents(taskId: Ref<string>, detail: Ref<TaskDetail | null>) {
  const events = ref<TaskEvent[]>([])
  const seenIds = new Set<string>()
  let lastEventId: string | null = null
  let reconnectDelayMs = 1000
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let abort: AbortController | null = null
  let disposed = false

  function clearReconnect() {
    if (reconnectTimer != null) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
  }

  function stopStream() {
    if (abort != null) {
      abort.abort()
      abort = null
    }
  }

  function appendEvent(event: TaskEvent) {
    if (!event.id || seenIds.has(event.id)) {
      return
    }
    seenIds.add(event.id)
    lastEventId = event.id
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

  function handleSseEvent(type: string, data: string, idFromFrame: string) {
    reconnectDelayMs = 1000
    let payload: Record<string, unknown> = {}
    try {
      payload = data ? (JSON.parse(data) as Record<string, unknown>) : {}
    } catch {
      payload = { message: data }
    }
    const resolvedType =
      type && type !== 'message' ? type : String(payload.type ?? payload.eventType ?? 'event')
    const id =
      idFromFrame ||
      (payload.attemptId != null && payload.seq != null
        ? `${payload.attemptId}:${payload.seq}`
        : String(payload.id ?? ''))
    if (!id) {
      return
    }
    appendEvent({
      id,
      type: resolvedType,
      message: String(payload.message ?? payload.summary ?? payload.payloadJson ?? data),
      at: (payload.eventTime as string | undefined) ?? (payload.at as string | undefined),
      terminal: Boolean(payload.terminal) || TERMINAL_TYPES.has(resolvedType),
    })
  }

  function scheduleReconnect() {
    if (disposed || !taskId.value) {
      return
    }
    clearReconnect()
    reconnectTimer = setTimeout(() => {
      reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000)
      void connect()
    }, reconnectDelayMs)
  }

  async function connect() {
    stopStream()
    clearReconnect()
    if (!taskId.value || disposed) {
      return
    }
    const url = taskEventsUrl(taskId.value)
    const controller = new AbortController()
    abort = controller
    try {
      await consumeSse(url, lastEventId, controller.signal, { onEvent: handleSseEvent })
      if (!disposed && !controller.signal.aborted) {
        scheduleReconnect()
      }
    } catch {
      if (disposed || controller.signal.aborted) {
        return
      }
      scheduleReconnect()
    }
  }

  watch(
    taskId,
    (id) => {
      events.value = []
      seenIds.clear()
      lastEventId = null
      reconnectDelayMs = 1000
      clearReconnect()
      stopStream()
      if (id) {
        void connect()
      }
    },
    { immediate: true },
  )

  onBeforeUnmount(() => {
    disposed = true
    clearReconnect()
    stopStream()
  })

  return {
    events,
    refreshDetail,
  }
}
