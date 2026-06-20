<script setup lang="ts">
// Topbar upload-status menu. Reads the global upload queue (uploads survive navigation), shows an active
// count badge and a popover listing each file's status. Only visible while there's something in the queue.
import { onBeforeUnmount, onMounted, ref } from 'vue'
import Button from 'primevue/button'
import { activeUploadCount, clearFinishedUploads, hasUploads, uploadItems } from '../../features/uploads/useUploadQueue'
import { t } from '../../i18n/i18n'

const open = ref(false)
const root = ref<HTMLElement | null>(null)

function toggle() { open.value = !open.value }
function close() { open.value = false }

function onDocPointerDown(e: PointerEvent) {
  if (open.value && root.value && e.target instanceof Node && !root.value.contains(e.target)) close()
}

onMounted(() => document.addEventListener('pointerdown', onDocPointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocPointerDown))

const icon = (status: string) =>
  status === 'done' ? 'pi pi-check' : status === 'error' ? 'pi pi-times' : status === 'uploading' ? 'pi pi-spin pi-spinner' : 'pi pi-clock'
</script>

<template>
  <div v-if="hasUploads" ref="root" class="upload-menu">
    <button type="button" class="upload-trigger-btn" :title="t('uploads.title')" :aria-label="t('uploads.title')" @click="toggle">
      <span class="pi pi-cloud-upload" />
      <span v-if="activeUploadCount" class="upload-badge">{{ activeUploadCount }}</span>
    </button>
    <section v-if="open" class="upload-panel" aria-live="polite">
      <header class="upload-panel-head">
        <h3>{{ t('uploads.title') }}</h3>
        <Button type="button" text size="small" :label="t('uploads.clear')" @click="clearFinishedUploads" />
      </header>
      <ul class="upload-list">
        <li v-for="item in uploadItems" :key="item.id" :class="['upload-row', `is-${item.status}`]">
          <span :class="icon(item.status)" />
          <span class="upload-name" :title="item.name">{{ item.name }}</span>
          <small v-if="item.status === 'error'" class="upload-err">{{ item.error }}</small>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.upload-menu { position: relative; margin-left: auto; }
.upload-trigger-btn { position: relative; background: none; border: none; cursor: pointer; color: inherit; font-size: 1.1rem; padding: 0.35rem; border-radius: 8px; }
.upload-trigger-btn:hover { background: var(--p-content-border-color, rgba(127,127,127,0.15)); }
.upload-badge { position: absolute; top: -2px; right: -2px; min-width: 16px; height: 16px; padding: 0 4px; border-radius: 999px; background: var(--p-primary-color, #6366f1); color: #fff; font-size: 0.7rem; line-height: 16px; text-align: center; }
.upload-panel { position: absolute; top: calc(100% + 6px); right: 0; z-index: 60; width: min(360px, 90vw); background: var(--p-content-background, #1e1e1e); border: 1px solid var(--p-content-border-color, rgba(127,127,127,0.25)); border-radius: 10px; padding: 0.5rem; box-shadow: 0 8px 24px rgba(0,0,0,0.25); }
.upload-panel-head { display: flex; align-items: center; justify-content: space-between; padding: 0.25rem 0.4rem; }
.upload-panel-head h3 { margin: 0; font-size: 0.9rem; }
.upload-list { list-style: none; margin: 0; padding: 0; max-height: 50vh; overflow: auto; }
.upload-row { display: flex; align-items: center; gap: 0.5rem; padding: 0.4rem; border-radius: 8px; }
.upload-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-err { color: var(--p-red-400, #f87171); max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.upload-row.is-done .pi { color: var(--p-green-400, #34d399); }
.upload-row.is-error .pi { color: var(--p-red-400, #f87171); }
</style>
