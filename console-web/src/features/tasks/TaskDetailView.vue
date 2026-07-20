<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { fetchTaskDetail } from '../../api/http'
import type { TaskDetail } from '../../api/types'
import TaskTimeline from './TaskTimeline.vue'
import { useTaskEvents } from './useTaskEvents'

const route = useRoute()
const detail = ref<TaskDetail | null>(null)
const error = ref<string | null>(null)
const expandedLogs = ref<Record<number, boolean>>({})

const taskId = computed(() => String(route.params.taskId ?? ''))
const { events } = useTaskEvents(taskId, detail)

const timelineEvents = computed(() => {
  const fromDetail = detail.value?.events ?? []
  const merged = new Map<string, (typeof fromDetail)[number]>()
  for (const event of fromDetail) {
    merged.set(event.id, event)
  }
  for (const event of events.value) {
    merged.set(event.id, event)
  }
  return [...merged.values()]
})

async function load() {
  error.value = null
  if (!taskId.value) {
    detail.value = null
    return
  }
  try {
    detail.value = await fetchTaskDetail(taskId.value)
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}

function toggleLog(index: number) {
  expandedLogs.value = {
    ...expandedLogs.value,
    [index]: !expandedLogs.value[index],
  }
}

watch(taskId, () => {
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <main v-if="detail" data-test="task-detail">
    <h1>{{ detail.issueRef }}</h1>

    <section data-test="issue-snapshot">
      <h2>Issue snapshot and attachments</h2>
      <p>{{ detail.issue.title }}</p>
      <a
        v-if="detail.issue.url"
        :href="detail.issue.url"
        target="_blank"
        rel="noopener noreferrer"
      >{{ detail.issue.url }}</a>
      <ul>
        <li v-for="attachment in detail.issue.attachments" :key="attachment.url">
          <a :href="attachment.url" target="_blank" rel="noopener noreferrer">{{ attachment.name }}</a>
        </li>
      </ul>
    </section>

    <section data-test="completeness">
      <h2>Completeness decision and missing fields</h2>
      <p>{{ detail.completeness.decision }}</p>
      <ul>
        <li v-for="field in detail.completeness.missingFields" :key="field">{{ field }}</li>
      </ul>
    </section>

    <section data-test="profile">
      <h2>Project profile revision and Base SHA</h2>
      <p>revision {{ detail.profile.revision }}</p>
      <p>Base SHA {{ detail.profile.baseSha }}</p>
    </section>

    <section data-test="scheduling">
      <h2>Scheduler candidates, rejection reasons and chosen score</h2>
      <p>{{ detail.scheduling.reason }}</p>
      <p>chosen score {{ detail.scheduling.chosenScore }}</p>
      <ul>
        <li v-for="candidate in detail.scheduling.candidates" :key="candidate.nodeId">
          {{ candidate.nodeId }} / {{ candidate.score }}
          <span v-if="candidate.rejectionReason"> — {{ candidate.rejectionReason }}</span>
        </li>
      </ul>
    </section>

    <section data-test="attempt">
      <h2>Attempt/Pipeline/Job identifiers</h2>
      <p>attempt {{ detail.attempt.attemptId }}</p>
      <p>pipeline {{ detail.attempt.pipelineId }}</p>
      <p>job {{ detail.attempt.jobId }}</p>
      <p>executor {{ detail.attempt.executor }}</p>
    </section>

    <section data-test="timeline">
      <h2>OpenCode normalized event timeline</h2>
      <TaskTimeline :events="timelineEvents" />
    </section>

    <section data-test="commands">
      <h2>Commands, exit codes and expandable redacted logs</h2>
      <article v-for="(command, index) in detail.commands" :key="`${command.display}-${index}`">
        <p>{{ command.display }}</p>
        <p>exit {{ command.exitCode }}</p>
        <button type="button" @click="toggleLog(index)">
          {{ expandedLogs[index] ? 'Hide log' : 'Show log' }}
        </button>
        <pre v-if="expandedLogs[index]">{{ command.log }}</pre>
      </article>
    </section>

    <section data-test="patch">
      <h2>Changed files and patch summary</h2>
      <p>{{ detail.patch.summary }}</p>
      <ul>
        <li v-for="file in detail.patch.changedFiles" :key="file">{{ file }}</li>
      </ul>
    </section>

    <section data-test="artifacts">
      <h2>Artifact checksums</h2>
      <ul>
        <li v-for="artifact in detail.artifacts" :key="artifact.sha256">
          {{ artifact.name }} {{ artifact.sha256 }}
        </li>
      </ul>
    </section>

    <section data-test="publication">
      <h2>Branch, commit and Merge Request</h2>
      <p>branch {{ detail.mergeRequest.branch }}</p>
      <p>commit {{ detail.mergeRequest.commitSha }}</p>
      <a
        data-test="merge-request"
        :href="detail.mergeRequest.url"
        target="_blank"
        rel="noopener noreferrer"
      >{{ detail.mergeRequest.url }}</a>
    </section>

    <section data-test="transitions">
      <h2>State transitions and audit operations</h2>
      <ul>
        <li v-for="(item, index) in detail.stateTransitions" :key="`st-${index}`">
          {{ item.from }} → {{ item.to }} @ {{ item.at }}
        </li>
      </ul>
      <ul>
        <li v-for="(item, index) in detail.auditOperations" :key="`au-${index}`">
          {{ item.action }} by {{ item.actor }} @ {{ item.at }}
        </li>
      </ul>
    </section>
  </main>
  <main v-else-if="error">
    <p>{{ error }}</p>
  </main>
  <main v-else>
    <p>Loading…</p>
  </main>
</template>
