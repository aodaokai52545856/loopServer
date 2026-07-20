<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{ close: [] }>()

const reason = ref('invite a trusted developer laptop')
const allowedProjects = ref('backend-a')
const oneTime = ref<{
  id: string
  code: string
  expiresAt: string
  joinCommand: string
} | null>(null)
const closedSummary = ref<{ id: string; status: string } | null>(null)
const error = ref<string | null>(null)

async function createInvite() {
  error.value = null
  const projects = allowedProjects.value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  const response = await fetch('/api/admin/node-invites', {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason.value, allowedProjects: projects }),
  })
  if (!response.ok) {
    error.value = `Request failed: ${response.status}`
    return
  }
  oneTime.value = (await response.json()) as {
    id: string
    code: string
    expiresAt: string
    joinCommand: string
  }
}

function closeDialog() {
  if (oneTime.value) {
    closedSummary.value = { id: oneTime.value.id, status: 'CREATED' }
  }
  oneTime.value = null
  emit('close')
}
</script>

<template>
  <div data-test="create-invite-dialog" role="dialog">
    <h2>创建节点邀请</h2>
    <p v-if="error">{{ error }}</p>
    <template v-if="!oneTime && !closedSummary">
      <label>
        原因
        <input v-model="reason" />
      </label>
      <label>
        项目白名单（逗号分隔）
        <input v-model="allowedProjects" />
      </label>
      <button data-test="create-invite" type="button" @click="createInvite">创建</button>
      <button type="button" @click="closeDialog">取消</button>
    </template>
    <template v-else-if="oneTime">
      <p data-test="invite-code">邀请码（仅显示一次）：{{ oneTime.code }}</p>
      <p>过期时间：{{ oneTime.expiresAt }}</p>
      <p data-test="join-command">{{ oneTime.joinCommand }}</p>
      <button data-test="close-invite" type="button" @click="closeDialog">关闭</button>
    </template>
    <template v-else-if="closedSummary">
      <p>邀请 ID：{{ closedSummary.id }}</p>
      <p>状态：{{ closedSummary.status }}</p>
    </template>
  </div>
</template>
