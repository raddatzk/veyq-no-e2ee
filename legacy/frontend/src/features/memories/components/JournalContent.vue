<script setup lang="ts">
// Block-based journal renderer for the E2EE backend. Splits the sealed Markdown into text + embed tokens
// (::media / ::chapter / ::track / ::playlist) and resolves each: media thumbnails are fetched as encrypted
// blobs and decrypted to object URLs client-side (the server never serves plaintext bytes); chapter embeds
// link to the child memory; track/playlist render as labelled cards (rich map/streaming UI is a follow-up).
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { renderMarkdown } from '../../memory/journal/markdown'
import { splitJournalBlocks, type JournalBlock } from '../../memory/journal/journalEmbed'
import { listMedia, downloadBlob, type MediaItem } from '../../uploads/media/media'
import type { UnlockedSession } from '../../account/login/login'

const props = defineProps<{
  apiBase: string
  session: UnlockedSession
  scopeId: string
  nodeId: string
  contentKey: Uint8Array
  markdown: string
}>()

const router = useRouter()
const { t } = useI18n()

const blocks = computed<JournalBlock[]>(() => splitJournalBlocks(props.markdown))
const mediaById = ref<Map<string, MediaItem>>(new Map())
const urlById = ref<Map<string, string>>(new Map())
const objectUrls: string[] = []

function referencedMediaIds(): string[] {
  const ids = new Set<string>()
  for (const b of blocks.value) if (b.type === 'embed' && b.embed.kind === 'media') b.embed.ids.forEach((id) => ids.add(id))
  return [...ids]
}

async function resolveMedia() {
  const ids = referencedMediaIds()
  if (ids.length === 0) return
  let items: MediaItem[]
  try {
    items = await listMedia(props.apiBase, props.session, props.scopeId, props.nodeId)
  } catch {
    return // media list unavailable — embeds fall back to a placeholder
  }
  const map = new Map(items.map((m) => [m.id, m]))
  mediaById.value = map
  // Decrypt the smallest available derivative (thumbnail → preview → original) into an object URL.
  await Promise.all(ids.map(async (id) => {
    const item = map.get(id)
    if (!item) return
    const ref = item.derivatives.find((d) => d.kind === 'THUMBNAIL')?.blob
      ?? item.derivatives.find((d) => d.kind === 'PREVIEW')?.blob
      ?? item.original
    try {
      const bytes = await downloadBlob(props.apiBase, props.session, props.scopeId, props.contentKey, ref)
      const url = URL.createObjectURL(new Blob([bytes.slice().buffer], { type: item.mimeType }))
      objectUrls.push(url)
      urlById.value = new Map(urlById.value).set(id, url)
    } catch {
      /* leave unresolved → placeholder */
    }
  }))
}

onMounted(resolveMedia)
watch(() => props.markdown, resolveMedia)
onBeforeUnmount(() => { objectUrls.forEach((u) => URL.revokeObjectURL(u)) })
</script>

<template>
  <div class="journal-content">
    <template v-for="(block, i) in blocks" :key="i">
      <!-- Markdown text -->
      <div v-if="block.type === 'markdown'" class="journal-html" v-html="renderMarkdown(block.markdown)" />

      <!-- Media embed: one or more thumbnails -->
      <div v-else-if="block.embed.kind === 'media'" class="journal-embed journal-media-embed">
        <figure v-for="id in block.embed.ids" :key="id" class="journal-media-figure">
          <img v-if="urlById.get(id)" :src="urlById.get(id)" :alt="block.embed.label || id" />
          <div v-else class="journal-media-missing">{{ t('journalView.mediaUnavailable') }}</div>
        </figure>
        <figcaption v-if="block.embed.label" class="journal-embed-caption">{{ block.embed.label }}</figcaption>
      </div>

      <!-- Chapter embed: link to the child memory -->
      <button
        v-else-if="block.embed.kind === 'chapter'"
        type="button"
        class="journal-embed journal-chapter-embed"
        @click="router.push(`/m/${block.embed.ids[0]}`)"
      >
        <i class="pi pi-book" />
        <span>{{ block.embed.label || t('journalView.chapter') }}</span>
        <i class="pi pi-angle-right" />
      </button>

      <!-- Track / playlist embeds: labelled cards (rich map/streaming UI to follow) -->
      <div v-else class="journal-embed journal-card-embed">
        <i :class="block.embed.kind === 'track' ? 'pi pi-map' : 'pi pi-list'" />
        <span>{{ block.embed.label || (block.embed.kind === 'track' ? t('journalView.track') : t('journalView.playlist')) }}</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.journal-content :deep(h1) { font-size: 1.6rem; margin: 1.2rem 0 0.6rem; }
.journal-content :deep(h2) { font-size: 1.3rem; margin: 1rem 0 0.5rem; }
.journal-content :deep(p) { line-height: 1.6; }
.journal-content :deep(a) { color: var(--p-primary-color, #5b4df5); }
.journal-media-embed {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin: 0.75rem 0;
}
.journal-media-figure { margin: 0; }
.journal-media-figure img {
  max-width: 100%;
  max-height: 320px;
  border-radius: 10px;
  display: block;
}
.journal-media-missing {
  padding: 1.5rem;
  border: 1px dashed var(--p-content-border-color, rgba(127, 127, 127, 0.3));
  border-radius: 10px;
  opacity: 0.6;
  font-size: 0.9rem;
}
.journal-embed-caption { width: 100%; opacity: 0.7; font-size: 0.85rem; }
.journal-chapter-embed,
.journal-card-embed {
  display: flex;
  align-items: center;
  gap: 0.6rem;
  width: 100%;
  margin: 0.5rem 0;
  padding: 0.75rem 1rem;
  border: 1px solid var(--p-content-border-color, rgba(127, 127, 127, 0.25));
  border-radius: 10px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  text-align: left;
  font-size: 1rem;
}
.journal-chapter-embed:hover { border-color: var(--p-primary-color, #5b4df5); }
.journal-chapter-embed .pi-angle-right { margin-left: auto; opacity: 0.5; }
.journal-card-embed { cursor: default; }
</style>
