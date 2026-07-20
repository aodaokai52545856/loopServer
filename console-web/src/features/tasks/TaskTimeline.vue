<script setup lang="ts">
import type { TaskEvent } from '../../api/types'

defineProps<{
  events: TaskEvent[]
}>()
</script>

<template>
  <ol data-test="task-timeline">
    <li v-for="event in events" :key="event.id">
      <time v-if="event.at">{{ event.at }}</time>
      <strong>{{ event.type }}</strong>
      <span>{{ event.message }}</span>
    </li>
  </ol>
</template>
