<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { fetchDashboard } from '../../api/http'
import type { DashboardSummary } from '../../api/types'

const summary = ref<DashboardSummary | null>(null)
const error = ref<string | null>(null)

const taskTotal = computed(() =>
  (summary.value?.tasksByState ?? []).reduce((sum, row) => sum + Number(row.count), 0),
)
const nodeTotal = computed(() =>
  (summary.value?.nodesByState ?? []).reduce((sum, row) => sum + Number(row.count), 0),
)

onMounted(async () => {
  try {
    summary.value = await fetchDashboard()
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
    summary.value = { tasksByState: [], nodesByState: [] }
  }
})
</script>

<template>
  <main data-test="dashboard">
    <h1>循环工程</h1>
    <p v-if="error">{{ error }}</p>
    <section>
      <strong>{{ taskTotal }}</strong><span>修复任务</span>
    </section>
    <section>
      <strong>{{ nodeTotal }}</strong><span>节点</span>
    </section>
    <section v-if="summary">
      <h2>任务状态</h2>
      <ul>
        <li v-for="row in summary.tasksByState" :key="row.state">
          {{ row.state }}: {{ row.count }}
        </li>
      </ul>
      <h2>节点状态</h2>
      <ul>
        <li v-for="row in summary.nodesByState" :key="row.state">
          {{ row.state }}: {{ row.count }}
        </li>
      </ul>
    </section>
    <p>
      <a href="/tasks">任务列表</a>
    </p>
  </main>
</template>
