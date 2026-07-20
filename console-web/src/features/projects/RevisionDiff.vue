<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  left: string
  right: string
}>()

const leftLines = computed(() => (props.left || '').split('\n'))
const rightLines = computed(() => (props.right || '').split('\n'))
</script>

<template>
  <section data-test="revision-diff">
    <h3>Revision Diff</h3>
    <div class="diff">
      <pre>{{ leftLines.join('\n') }}</pre>
      <pre>{{ rightLines.join('\n') }}</pre>
    </div>
  </section>
</template>
