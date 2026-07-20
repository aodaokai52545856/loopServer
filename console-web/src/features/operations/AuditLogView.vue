<script setup lang="ts">
import { onMounted, ref } from 'vue'

type AuditItem = {
  id: number
  actorType: string
  actorId: string
  action: string
  objectType: string
  objectId: string
  requestId: string
  detailJson: string
  createdAt: string
}

const items = ref<AuditItem[]>([])
const error = ref<string | null>(null)
const actor = ref('')
const object = ref('')

onMounted(() => {
  void load()
})

async function load() {
  error.value = null
  const params = new URLSearchParams()
  if (actor.value) params.set('actor', actor.value)
  if (object.value) params.set('object', object.value)
  params.set('limit', '50')
  try {
    const response = await fetch(`/api/audit?${params.toString()}`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    const body = (await response.json()) as { items: AuditItem[] }
    items.value = body.items ?? []
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
    items.value = []
  }
}
</script>

<template>
  <main data-test="audit-log">
    <h1>审计日志</h1>
    <p v-if="error">{{ error }}</p>
    <label>
      Actor
      <input v-model="actor" />
    </label>
    <label>
      Object
      <input v-model="object" />
    </label>
    <button type="button" @click="load">查询</button>
    <table>
      <thead>
        <tr>
          <th>时间</th>
          <th>Actor</th>
          <th>动作</th>
          <th>对象</th>
          <th>Request</th>
          <th>详情</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td>{{ item.createdAt }}</td>
          <td>{{ item.actorType }}/{{ item.actorId }}</td>
          <td>{{ item.action }}</td>
          <td>{{ item.objectType }}/{{ item.objectId }}</td>
          <td>{{ item.requestId }}</td>
          <td>{{ item.detailJson }}</td>
        </tr>
      </tbody>
    </table>
  </main>
</template>
