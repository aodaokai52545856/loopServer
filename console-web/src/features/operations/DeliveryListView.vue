<script setup lang="ts">
import { onMounted, ref } from 'vue'

type DeliveryItem = {
  eventUuid?: string
  id?: string
  eventName?: string
  eventType?: string
  state: string
  attemptCount: number
  nextAttemptAt?: string | null
  lastError?: string | null
}

const tab = ref<'webhooks' | 'outbox'>('webhooks')
const items = ref<DeliveryItem[]>([])
const error = ref<string | null>(null)
const reason = ref('replay after gitlab recovered')
const auditMessage = ref<string | null>(null)
const roles = ref<string[]>([])

const canRetry = () => roles.value.includes('ADMIN')

onMounted(async () => {
  try {
    const sessionRes = await fetch('/api/session', { credentials: 'same-origin' })
    if (sessionRes.ok) {
      const session = (await sessionRes.json()) as { roles?: string[] }
      roles.value = session.roles ?? []
    }
  } catch {
    roles.value = []
  }
  await load()
})

async function load() {
  error.value = null
  const path =
    tab.value === 'webhooks' ? '/api/operations/webhooks?limit=50' : '/api/operations/outbox?limit=50'
  try {
    const response = await fetch(path, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    const body = (await response.json()) as { items: DeliveryItem[] }
    items.value = body.items ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
    items.value = []
  }
}

function redact(errorText: string | null | undefined): string {
  if (!errorText) return '—'
  return errorText
    .replace(/glpat-[A-Za-z0-9_-]+/g, '[REDACTED]')
    .replace(/(token|secret|password|api[_-]?key)=[^\s&]+/gi, '$1=[REDACTED]')
}

async function retryItem(item: DeliveryItem) {
  if (reason.value.length < 10 || reason.value.length > 500) {
    error.value = 'reason must be 10-500 characters'
    return
  }
  const path =
    tab.value === 'webhooks'
      ? `/api/operations/webhooks/${encodeURIComponent(String(item.eventUuid))}/retry`
      : `/api/operations/outbox/${encodeURIComponent(String(item.id))}/retry`
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason.value }),
  })
  if (!response.ok) {
    error.value = `Request failed: ${response.status}`
    return
  }
  const body = (await response.json()) as { auditId?: number }
  auditMessage.value = body.auditId != null ? `审计 #${body.auditId}` : '已提交'
  await load()
}

async function switchTab(next: 'webhooks' | 'outbox') {
  tab.value = next
  await load()
}
</script>

<template>
  <main data-test="delivery-list">
    <h1>Webhook / Outbox</h1>
    <p v-if="error">{{ error }}</p>
    <p v-if="auditMessage">{{ auditMessage }}</p>
    <div>
      <button type="button" @click="switchTab('webhooks')">Webhooks</button>
      <button type="button" @click="switchTab('outbox')">Outbox</button>
    </div>
    <label>
      重试原因
      <input v-model="reason" data-test="retry-reason" />
    </label>
    <table>
      <thead>
        <tr>
          <th>ID</th>
          <th>类型</th>
          <th>状态</th>
          <th>尝试次数</th>
          <th>下次重试</th>
          <th>错误</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="String(item.eventUuid ?? item.id)">
          <td>{{ item.eventUuid ?? item.id }}</td>
          <td>{{ item.eventName ?? item.eventType }}</td>
          <td>{{ item.state }}</td>
          <td>{{ item.attemptCount }}</td>
          <td>{{ item.nextAttemptAt || '—' }}</td>
          <td>{{ redact(item.lastError) }}</td>
          <td>
            <button
              v-if="canRetry()"
              type="button"
              data-test="retry"
              @click="retryItem(item)"
            >
              重试
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </main>
</template>
