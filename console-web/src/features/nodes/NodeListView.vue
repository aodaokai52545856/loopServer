<script setup lang="ts">
import { onMounted, ref } from 'vue'

type NodeListItem = {
  id: string
  name: string
  ownerId: string
  state: string
  concurrencyLimit: number
  activeSlots: number
  lastHeartbeatAt?: string | null
}

const items = ref<NodeListItem[]>([])
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    const response = await fetch('/api/nodes?limit=50', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    const body = (await response.json()) as { items: NodeListItem[] }
    items.value = body.items ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
})
</script>

<template>
  <main data-test="node-list">
    <h1>节点</h1>
    <p v-if="error">{{ error }}</p>
    <table>
      <thead>
        <tr>
          <th>名称</th>
          <th>负责人</th>
          <th>状态</th>
          <th>槽位</th>
          <th>心跳</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="node in items" :key="node.id">
          <td>
            <a :href="`/nodes/${encodeURIComponent(node.id)}`">{{ node.name }}</a>
          </td>
          <td>{{ node.ownerId }}</td>
          <td>{{ node.state }}</td>
          <td>{{ node.activeSlots }} / {{ node.concurrencyLimit }}</td>
          <td>{{ node.lastHeartbeatAt || '—' }}</td>
        </tr>
      </tbody>
    </table>
  </main>
</template>
