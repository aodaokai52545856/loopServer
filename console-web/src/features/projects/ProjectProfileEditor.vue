<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import RevisionDiff from './RevisionDiff.vue'

type Violation = { path?: string; pointer?: string; message?: string; detail?: string }
type Revision = {
  revision: number
  configJson: string
  publishedBy: string
  publishedAt: string
}

const projectKey = ref('backend-a')
const repository = ref('group/backend-a')
const defaultBranch = ref('main')
const modulesText = ref('services/order')
const contextPathsText = ref('README.md')
const validationProgram = ref('mvn')
const validationArgsText = ref('-B,test')
const revisions = ref<Revision[]>([])
const selectedLeft = ref<number | null>(null)
const selectedRight = ref<number | null>(null)
const fieldErrors = ref<Record<string, string>>({})
const message = ref<string | null>(null)
const error = ref<string | null>(null)

const leftJson = computed(() => revisions.value.find((r) => r.revision === selectedLeft.value)?.configJson ?? '')
const rightJson = computed(
  () => revisions.value.find((r) => r.revision === selectedRight.value)?.configJson ?? '',
)

onMounted(() => {
  void loadRevisions()
})

watch(projectKey, () => {
  void loadRevisions()
})

async function loadRevisions() {
  fieldErrors.value = {}
  try {
    const response = await fetch(`/api/projects/${encodeURIComponent(projectKey.value)}/revisions`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`)
    }
    const body = (await response.json()) as { items: Revision[] }
    revisions.value = body.items ?? []
    if (revisions.value.length >= 2) {
      selectedRight.value = revisions.value[0]!.revision
      selectedLeft.value = revisions.value[1]!.revision
    } else if (revisions.value.length === 1) {
      selectedRight.value = revisions.value[0]!.revision
      selectedLeft.value = revisions.value[0]!.revision
    }
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}

function splitLines(value: string): string[] {
  return value
    .split(/[\n,]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

function buildConfig() {
  return {
    repository: repository.value,
    defaultBranch: defaultBranch.value,
    modules: splitLines(modulesText.value),
    contextPaths: splitLines(contextPathsText.value),
    validationCommands: [
      {
        program: validationProgram.value,
        args: splitLines(validationArgsText.value),
        timeoutSeconds: 1200,
      },
    ],
    allowedOs: ['linux'],
    allowedNodeIds: [],
    allowedNodeOwnerIds: [],
    requiredTools: { java: '>=21' },
    forbiddenPaths: ['.git/**'],
    maxChangedFiles: 40,
    maxPatchBytes: 1048576,
    maxRepairRounds: 2,
    maxExternalAttempts: 2,
    retryFunctionalFailure: false,
    targetBranch: 'main',
    branchPrefix: 'repair/',
    reviewers: [],
  }
}

async function publish(config: Record<string, unknown>) {
  fieldErrors.value = {}
  message.value = null
  error.value = null
  const response = await fetch(`/api/projects/${encodeURIComponent(projectKey.value)}/revisions`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
  if (response.status === 400) {
    const problem = (await response.json()) as {
      detail?: string
      violations?: Violation[]
    }
    for (const violation of problem.violations ?? []) {
      const pointer = String(violation.path ?? violation.pointer ?? '')
      fieldErrors.value[pointer] = String(violation.message ?? violation.detail ?? problem.detail ?? '')
    }
    error.value = problem.detail ?? '校验失败'
    return
  }
  if (!response.ok) {
    error.value = `Request failed: ${response.status}`
    return
  }
  const body = (await response.json()) as { revision: number }
  message.value = `已发布 revision ${body.revision}`
  await loadRevisions()
}

async function publishCurrent() {
  await publish(buildConfig())
}

async function rollback(revision: number) {
  const found = revisions.value.find((r) => r.revision === revision)
  if (!found) return
  const config = JSON.parse(found.configJson) as Record<string, unknown>
  await publish(config)
}
</script>

<template>
  <main data-test="project-profile-editor">
    <h1>项目配置</h1>
    <p v-if="error">{{ error }}</p>
    <p v-if="message">{{ message }}</p>

    <label>
      项目 Key
      <input v-model="projectKey" data-test="project-key" />
    </label>
    <label>
      仓库
      <input v-model="repository" />
    </label>
    <label>
      默认分支
      <input v-model="defaultBranch" />
    </label>
    <label>
      modules（数组，逗号/换行）
      <textarea v-model="modulesText" data-test="modules" />
      <span v-if="fieldErrors['modules/0']">{{ fieldErrors['modules/0'] }}</span>
    </label>
    <label>
      contextPaths（数组）
      <textarea v-model="contextPathsText" />
    </label>
    <label>
      验证程序
      <input v-model="validationProgram" data-test="validation-program" />
      <span v-if="fieldErrors['validationCommands/0/program']">
        {{ fieldErrors['validationCommands/0/program'] }}
      </span>
    </label>
    <label>
      验证参数（数组）
      <input v-model="validationArgsText" data-test="validation-args" />
    </label>

    <button data-test="publish" type="button" @click="publishCurrent">发布新版本</button>

    <section>
      <h2>历史版本</h2>
      <ul>
        <li v-for="rev in revisions" :key="rev.revision">
          #{{ rev.revision }} by {{ rev.publishedBy }} at {{ rev.publishedAt }}
          <button type="button" @click="rollback(rev.revision)">回滚为新版本</button>
        </li>
      </ul>
      <label>
        左
        <select v-model.number="selectedLeft">
          <option v-for="rev in revisions" :key="`l-${rev.revision}`" :value="rev.revision">
            {{ rev.revision }}
          </option>
        </select>
      </label>
      <label>
        右
        <select v-model.number="selectedRight">
          <option v-for="rev in revisions" :key="`r-${rev.revision}`" :value="rev.revision">
            {{ rev.revision }}
          </option>
        </select>
      </label>
      <RevisionDiff :left="leftJson" :right="rightJson" />
    </section>
  </main>
</template>
