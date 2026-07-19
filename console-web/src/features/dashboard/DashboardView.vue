<script setup lang="ts">
const cards = [
  { label: '修复任务', value: 0 },
  { label: '节点', value: 0 },
]
</script>

<template>
  <main>
    <h1>循环工程</h1>
    <section v-for="card in cards" :key="card.label">
      <strong>{{ card.value }}</strong><span>{{ card.label }}</span>
    </section>
  </main>
</template>
