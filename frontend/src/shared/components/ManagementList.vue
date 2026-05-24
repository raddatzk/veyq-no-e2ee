<template>
  <div class="management-list">
    <div v-if="$slots.header" class="management-list-header">
      <slot name="header" />
    </div>
    <div class="management-list-items">
      <slot />
    </div>
  </div>
</template>
