<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import CreateInviteDialog from './CreateInviteDialog.vue'

type Session = { roles?: string[] }

type NodeResources = {
  cpuPercent?: number
  memoryAvailableBytes?: number
  diskAvailableBytes?: number
}

type NodeDetail = {
  id: string
  name: string
  ownerId: string
  description: string
  state: string
  enabled: boolean
  concurrencyLimit: number
  activeSlots: number
  desiredRevision: number
  appliedRevision: number
  capabilitiesJson?: string
  allowedProjectsJson?: string
  lastHeartbeatAt?: string | null
  lastError?: string | null
  runnerState?: string
  resources?: NodeResources | null
  recentAttempts?: Array<{ id: string; state: string }>
}

const route = useRoute()
const node = ref<NodeDetail | null>(null)
const error = ref<string | null>(null)
const session = ref<Session | null>(null)
const confirmDrain = ref(false)
const actionMessage = ref<string | null>(null)
const showInvite = ref(false)
const reason = ref('pause after current jobs finish')

const canManage = computed(() => {
  const roles = session.value?.roles ?? []
  return roles.includes('ADMIN') || roles.includes('NODE_OWNER')
})

const capabilityLabel = computed(() => {
  const caps = parseJson(node.value?.capabilitiesJson)
  const os = String(caps.os ?? '')
  const arch = String(caps.arch ?? '')
  return `${os} ${arch}`.trim()
})

const hasOpenCode = computed(() => {
  const caps = parseJson(node.value?.capabilitiesJson)
  const tools = (caps.tools as Record<string, unknown> | undefined) ?? caps
  return Boolean(tools.opencode || tools.OpenCode)
})

const slotsLabel = computed(() => {
  if (!node.value) return ''
  return `${node.value.activeSlots} / ${node.value.concurrencyLimit}`
})

const revisionLabel = computed(() => {
  if (!node.value) return ''
  return `配置 ${node.value.desiredRevision} / 已应用 ${node.value.appliedRevision}`
})

const heartbeatAge = computed(() => {
  const raw = node.value?.lastHeartbeatAt
  if (!raw) return '未知'
  const ageMs = Date.now() - new Date(raw).getTime()
  const seconds = Math.max(0, Math.round(ageMs / 1000))
  return `${seconds} 秒前`
})

const allowedProjects = computed(() => {
  try {
    const parsed = JSON.parse(node.value?.allowedProjectsJson ?? '[]') as unknown
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
})

const resourceLabels = computed(() => {
  const resources = node.value?.resources
  if (!resources) {
    return null
  }
  return {
    cpu: resources.cpuPercent == null ? null : `CPU ${resources.cpuPercent}%`,
    memory:
      resources.memoryAvailableBytes == null
        ? null
        : `内存 ${formatBytes(resources.memoryAvailableBytes)}`,
    disk:
      resources.diskAvailableBytes == null
        ? null
        : `磁盘 ${formatBytes(resources.diskAvailableBytes)}`,
  }
})

function formatBytes(value: number): string {
  if (value < 1024) {
    return `${value} B`
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KiB`
  }
  if (value < 1024 * 1024 * 1024) {
    return `${(value / (1024 * 1024)).toFixed(1)} MiB`
  }
  return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GiB`
}

onMounted(async () => {
  try {
    const sessionRes = await fetch('/api/session', { credentials: 'same-origin' })
    if (sessionRes.ok) {
      session.value = (await sessionRes.json()) as Session
    }
  } catch {
    session.value = { roles: [] }
  }
  await loadNode()
})

async function loadNode() {
  const nodeId = String(route.params.nodeId ?? '')
  try {
    const response = await fetch(`/api/nodes/${encodeURIComponent(nodeId)}`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    node.value = (await response.json()) as NodeDetail
    error.value = null
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}

function parseJson(raw?: string): Record<string, unknown> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return {}
  }
}

async function postAction(path: string) {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason.value }),
  })
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status}`)
  }
  const body = (await response.json()) as { auditId?: number }
  actionMessage.value = body.auditId != null ? `审计 #${body.auditId}` : '已提交'
  await loadNode()
}

async function onDrainClick() {
  if (!confirmDrain.value) {
    confirmDrain.value = true
    return
  }
  await postAction(`/api/nodes/${encodeURIComponent(node.value!.id)}/drain`)
  confirmDrain.value = false
}
</script>

<template>
  <main data-test="node-detail" v-if="node">
    <h1>{{ node.name }}</h1>
    <p v-if="error">{{ error }}</p>
    <p v-if="actionMessage">{{ actionMessage }}</p>

    <section>
      <p>负责人：{{ node.ownerId }}</p>
      <p>{{ node.description }}</p>
      <p>{{ capabilityLabel }}</p>
      <p v-if="hasOpenCode">OpenCode</p>
      <p>{{ slotsLabel }}</p>
      <p>{{ revisionLabel }}</p>
      <p>{{ heartbeatAge }}</p>
      <p v-if="resourceLabels?.cpu" data-test="cpu">{{ resourceLabels.cpu }}</p>
      <p v-if="resourceLabels?.memory" data-test="memory">{{ resourceLabels.memory }}</p>
      <p v-if="resourceLabels?.disk" data-test="disk">{{ resourceLabels.disk }}</p>
      <p>状态：{{ node.state }} / Runner {{ node.runnerState || 'unknown' }}</p>
      <p>白名单：{{ allowedProjects.join(', ') || '无' }}</p>
      <p v-if="node.lastError">最近错误：{{ node.lastError }}</p>
    </section>

    <section v-if="canManage">
      <label>
        原因
        <input v-model="reason" data-test="reason" />
      </label>
      <button data-test="drain" type="button" @click="onDrainClick">Drain</button>
      <p v-if="confirmDrain">当前任务完成后停止接单</p>
      <button
        data-test="resume"
        type="button"
        @click="postAction(`/api/nodes/${encodeURIComponent(node.id)}/resume`)"
      >
        Resume
      </button>
      <button
        data-test="disable"
        type="button"
        @click="postAction(`/api/nodes/${encodeURIComponent(node.id)}/disable`)"
      >
        Disable
      </button>
      <button
        data-test="rotate-cert"
        type="button"
        @click="postAction(`/api/nodes/${encodeURIComponent(node.id)}/certificate-rotation`)"
      >
        轮换证书
      </button>
    </section>

    <section v-if="(session?.roles ?? []).includes('ADMIN')">
      <button data-test="open-invite" type="button" @click="showInvite = true">创建邀请</button>
      <CreateInviteDialog v-if="showInvite" @close="showInvite = false" />
    </section>

    <section v-if="node.recentAttempts?.length">
      <h2>最近 Attempt</h2>
      <ul>
        <li v-for="attempt in node.recentAttempts" :key="attempt.id">
          {{ attempt.id }} — {{ attempt.state }}
        </li>
      </ul>
    </section>

    <p><a href="/nodes">返回节点列表</a></p>
  </main>
  <main v-else>
    <p>{{ error || '加载中…' }}</p>
  </main>
</template>
