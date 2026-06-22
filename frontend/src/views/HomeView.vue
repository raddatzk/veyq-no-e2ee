<script setup lang="ts">
// Minimal personal-space browser for the E2EE backend: enter the personal scope, list its folders/memories
// (names decrypted client-side), drill into folders, and open a memory's journal. A focused first step of the
// memories revival — the full rich memory UI (cover grid, media, context panel) is a later increment.
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import { ensureActiveSpaceForRoute } from '../app/spaces'
import { loadLevel, type LevelEntry } from '../features/home/home'
import { requireSession, session } from '../app/session'

const router = useRouter()
const { t } = useI18n()

const entries = ref<LevelEntry[]>([])
const stack = ref<{ id: string; name: string }[]>([])
const loading = ref(false)
const error = ref('')

onMounted(() => open(undefined))

async function open(parentId: string | undefined) {
  loading.value = true
  error.value = ''
  try {
    const active = await ensureActiveSpaceForRoute(null)
    entries.value = await loadLevel(session.apiBase, requireSession(), active, parentId)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : t('homeView.loadFailed')
  } finally {
    loading.value = false
  }
}

function enter(entry: LevelEntry) {
  if (entry.node.type === 'FOLDER') {
    stack.value.push({ id: entry.node.id, name: entry.name })
    void open(entry.node.id)
  } else {
    void router.push(`/m/${entry.node.id}`)
  }
}

function back() {
  stack.value.pop()
  const parent = stack.value[stack.value.length - 1]
  void open(parent?.id)
}
</script>

<template>
  <div class="veyq-page veyq-page-narrow">
    <div class="home-head">
      <Button v-if="stack.length" type="button" icon="pi pi-arrow-left" :label="t('homeView.back')" text @click="back" />
      <h1>{{ stack.length ? stack[stack.length - 1].name : t('homeView.title') }}</h1>
    </div>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('common.loading') }}</span>
    </div>

    <template v-else>
      <Message v-if="entries.length === 0" severity="info" :closable="false">{{ t('homeView.empty') }}</Message>
      <ul v-else class="home-list">
        <li v-for="entry in entries" :key="entry.node.id">
          <button type="button" class="home-item" @click="enter(entry)">
            <i :class="entry.node.type === 'FOLDER' ? 'pi pi-folder' : 'pi pi-book'" />
            <span>{{ entry.name }}</span>
            <i class="pi pi-angle-right home-item-go" />
          </button>
        </li>
      </ul>
    </template>
  </div>
</template>

<style scoped>
.home-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.home-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.home-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.85rem 1rem;
  border: 1px solid var(--p-content-border-color, rgba(127, 127, 127, 0.2));
  border-radius: 10px;
  background: var(--p-content-background, transparent);
  color: inherit;
  cursor: pointer;
  text-align: left;
  font-size: 1rem;
}
.home-item:hover {
  border-color: var(--p-primary-color, #5b4df5);
}
.home-item-go {
  margin-left: auto;
  opacity: 0.5;
}
</style>
