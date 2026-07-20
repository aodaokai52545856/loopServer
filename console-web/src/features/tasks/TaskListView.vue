<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchTasks } from '../../api/http'
import type { TaskListItem } from '../../api/types'

const route = useRoute()
const router = useRouter()
const items = ref<TaskListItem[]>([])
const error = ref<string | null>(null)

const filters = computed(() => ({
  projectKey: String(route.query.projectKey ?? ''),
  state: String(route.query.state ?? ''),
  nodeId: String(route.query.nodeId ?? ''),
  issue: String(route.query.issue ?? ''),
  module: String(route.query.module ?? ''),
  executor: String(route.query.executor ?? ''),
  from: String(route.query.from ?? ''),
  to: String(route.query.to ?? ''),
}))

async function load() {
  error.value = null
  try {
    const page = await fetchTasks({
      projectKey: filters.value.projectKey || undefined,
      state: filters.value.state || undefined,
      nodeId: filters.value.nodeId || undefined,
      issue: filters.value.issue || undefined,
      from: filters.value.from || undefined,
      to: filters.value.to || undefined,
      limit: 50,
    })
    items.value = page.items.filter((item) => {
      if (filters.value.module && item.moduleKey !== filters.value.module) {
        return false
      }
      if (filters.value.executor && item.executor !== filters.value.executor) {
        return false
      }
      return true
    })
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}

function updateFilter(key: keyof typeof filters.value, value: string) {
  const next = { ...route.query, [key]: value || undefined }
  void router.replace({ query: next })
}

watch(
  () => route.query,
  () => {
    void load()
  },
)

onMounted(() => {
  void load()
})
</script>

<template>
  <main data-test="task-list">
    <h1>修复任务</h1>

    <form @submit.prevent>
      <label>
        Issue
        <input
          :value="filters.issue"
          @change="updateFilter('issue', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        项目
        <input
          :value="filters.projectKey"
          @change="updateFilter('projectKey', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        模块
        <input
          :value="filters.module"
          @change="updateFilter('module', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        状态
        <input
          :value="filters.state"
          @change="updateFilter('state', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        节点
        <input
          :value="filters.nodeId"
          @change="updateFilter('nodeId', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <label>
        执行器
        <input
          :value="filters.executor"
          @change="updateFilter('executor', ($event.target as HTMLInputElement).value)"
        />
      </label>
    </form>

    <p v-if="error">{{ error }}</p>

    <table>
      <thead>
        <tr>
          <th>Issue</th>
          <th>项目/模块</th>
          <th>状态</th>
          <th>当前节点</th>
          <th>执行器</th>
          <th>等待时长</th>
          <th>修复时长</th>
          <th>更新时间</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td>
            <RouterLink :to="`/tasks/${item.id}`">{{ item.issueRef }}</RouterLink>
          </td>
          <td>{{ item.projectKey }}{{ item.moduleKey ? ` / ${item.moduleKey}` : '' }}</td>
          <td>{{ item.state }}</td>
          <td>{{ item.nodeId ?? '—' }}</td>
          <td>{{ item.executor ?? '—' }}</td>
          <td>{{ item.waitingDuration ?? '—' }}</td>
          <td>{{ item.repairDuration ?? '—' }}</td>
          <td>{{ item.updatedAt }}</td>
        </tr>
      </tbody>
    </table>
  </main>
</template>
