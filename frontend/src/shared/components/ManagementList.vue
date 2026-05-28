<template>
  <div class="veyq-list management-list">
    <div v-if="$slots.header" class="management-list-header">
      <slot name="header" />
    </div>
    <div class="veyq-list management-list-items">
      <slot />
    </div>
  </div>
</template>
