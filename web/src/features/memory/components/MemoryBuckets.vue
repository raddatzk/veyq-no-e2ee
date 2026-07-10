<script setup lang="ts">
// The "Beiträge" organizer (uc.memory.buckets, en.memory.buckets, cr-0113) — ported from the pre-E2EE
// MemoryAssetBuckets UX, rewired to the operator-blind data layer. A single-column view: a hierarchy Select
// (primary bucket per uploader → its sub-buckets), a 3-dot bucket menu (upload / create sub-bucket / rename /
// delete, gated to your OWN buckets), the reused MediaGrid filtered to the active bucket with a per-tile
// checkbox + menu, a batch toolbar, and a move dialog. The primary bucket is VIRTUAL until the first sub-bucket
// or upload materializes it (the server's lazy ensurePrimaryBucket). Names are sealed under K_mem — the client
// unseals; the server never sees a bucket name.
import { ref, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Select from 'primevue/select'
import Menu from 'primevue/menu'
import type { MenuItem } from 'primevue/menuitem'
import Dialog from 'primevue/dialog'
import MediaGrid from './MediaGrid.vue'
import { galleryDisplayAspectRatio } from '../mediaGridLayout'
import {
  listBuckets, createBucket, renameBucket, deleteBucket, moveBucketMedia, openBucketName, deleteMedia,
  unsealMeta, hasDerivatives, type MediaItem, type BucketItem,
} from '../media'
import { listScopeMembers } from '../../spaces/members'

const props = defineProps<{
  scopeId: string
  nodeId: string
  memoryKey: Uint8Array | null
  media: MediaItem[] // all live media in the Memory (each carrying ownerKey/bucketId)
  thumbs: Map<string, string> // mediaId → decrypted thumbnail object-url (owned by the parent gallery)
}>()
const emit = defineEmits<{
  changed: [] // a bucket/media mutation landed — the parent should reload media (ownerKey/bucketId change)
  openLightbox: [{ items: MediaItem[]; index: number }]
  uploadBucket: [string | null] // open the upload scoped to this bucket (null = your primary)
}>()

const { t, locale } = useI18n()
const SELF_PRIMARY = '__veyq_self_primary__'

type BucketVM = {
  id: string
  ownerKey: string
  parentBucketId: string | null
  isPrimary: boolean
  displayName: string
  mediaCount: number
  canManage: boolean // you may create/rename/delete/move here (your own bucket)
  virtual: boolean // client-only placeholder for your not-yet-materialized primary
}
type BucketRow = BucketVM & { depth: number }

const rawBuckets = ref<BucketVM[]>([])
const status = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const activeBucketId = ref('')
const selected = ref<Set<string>>(new Set())

const bucketMenu = ref<InstanceType<typeof Menu> | null>(null)
const mediaMenu = ref<InstanceType<typeof Menu> | null>(null)
const selectionMenu = ref<InstanceType<typeof Menu> | null>(null)
const menuMediaId = ref<string | null>(null)

const bucketDialog = ref<{ open: boolean; mode: 'create' | 'rename'; name: string; saving: boolean; error: string }>(
  { open: false, mode: 'create', name: '', saving: false, error: '' },
)
const deleteBucketDialog = ref<{ open: boolean; typed: string; saving: boolean; error: string }>(
  { open: false, typed: '', saving: false, error: '' },
)
const moveDialog = ref<{ open: boolean; target: string; saving: boolean; error: string }>(
  { open: false, target: '', saving: false, error: '' },
)
const deleteMediaDialog = ref<{ open: boolean; typed: string; count: number; saving: boolean }>(
  { open: false, typed: '', count: 0, saving: false },
)
const dropActive = ref(false)

// --- load + label resolution ------------------------------------------------

function labelFor(b: BucketItem, key: Uint8Array | null, members: Map<string, string>): string {
  if (!b.isPrimary) {
    return (key && b.sealedName && openBucketName(key, b.sealedName, 'sub')) || t('memory.buckets.contributor')
  }
  if (b.ownerKey.startsWith('share:')) {
    return (key && b.sealedName && openBucketName(key, b.sealedName, 'contributor')) || t('memory.buckets.contributor')
  }
  return members.get(b.ownerKey.slice('user:'.length)) ?? t('memory.buckets.member')
}

async function loadBuckets() {
  const key = props.memoryKey
  status.value = 'loading'
  try {
    const [raw, members] = await Promise.all([
      listBuckets(props.scopeId, props.nodeId),
      listScopeMembers(props.scopeId).catch(() => []),
    ])
    const names = new Map(members.map((m) => [m.userId, m.username]))
    rawBuckets.value = raw.map((b) => ({
      id: b.bucketId,
      ownerKey: b.ownerKey,
      parentBucketId: b.parentBucketId ?? null,
      isPrimary: b.isPrimary,
      displayName: labelFor(b, key, names),
      mediaCount: b.mediaCount,
      canManage: b.ownerKey.startsWith('user:'), // personal scope: the member bucket is yours (server enforces regardless)
      virtual: false,
    }))
    status.value = 'ready'
  } catch {
    status.value = 'error'
  }
}

watch(() => [props.nodeId, props.memoryKey] as const, () => { selected.value = new Set(); void loadBuckets() }, { immediate: true })

// --- the virtual own-primary + the flattened hierarchy ----------------------

// media that are yours and not yet in a materialized bucket → shown under the virtual primary before it exists.
const virtualPrimaryCount = computed(() =>
  props.media.filter((m) => (!m.ownerKey || m.ownerKey.startsWith('user:')) && !m.bucketId).length,
)

const bucketsWithSelf = computed<BucketVM[]>(() => {
  const list = rawBuckets.value
  if (list.some((b) => b.isPrimary && b.ownerKey.startsWith('user:'))) return list
  // no materialized own primary yet → synthesize a virtual one so you can create sub-buckets / upload
  return [
    {
      id: SELF_PRIMARY, ownerKey: 'user:self', parentBucketId: null, isPrimary: true,
      displayName: t('memory.buckets.you'), mediaCount: virtualPrimaryCount.value, canManage: true, virtual: true,
    },
    ...list,
  ]
})

const bucketRows = computed<BucketRow[]>(() => {
  const all = bucketsWithSelf.value
  const kids = new Map<string | null, BucketVM[]>()
  for (const b of all) (kids.get(b.parentBucketId) ?? kids.set(b.parentBucketId, []).get(b.parentBucketId)!).push(b)
  for (const arr of kids.values()) arr.sort((a, b) => a.displayName.localeCompare(b.displayName, locale.value))
  const rows: BucketRow[] = []
  const visit = (b: BucketVM, depth: number) => {
    rows.push({ ...b, depth })
    for (const c of kids.get(b.id) ?? []) visit(c, depth + 1)
  }
  for (const root of kids.get(null) ?? []) visit(root, 0)
  return rows
})

const activeBucket = computed<BucketRow | null>(
  () => bucketRows.value.find((b) => b.id === activeBucketId.value) ?? bucketRows.value[0] ?? null,
)
watch(bucketRows, (rows) => {
  if (!rows.some((r) => r.id === activeBucketId.value)) activeBucketId.value = rows[0]?.id ?? ''
}, { immediate: true })

const selectOptions = computed(() =>
  bucketRows.value.map((b) => ({ value: b.id, displayName: b.displayName, mediaCount: b.mediaCount, isPrimary: b.isPrimary, depth: b.depth })),
)

// media of the active bucket: a materialized bucket by bucketId; the virtual primary = your unbucketed media.
const filteredMedia = computed<MediaItem[]>(() => {
  const b = activeBucket.value
  if (!b) return []
  if (b.virtual) return props.media.filter((m) => (!m.ownerKey || m.ownerKey.startsWith('user:')) && !m.bucketId)
  return props.media.filter((m) => m.bucketId === b.id)
})

const aspectFor = (m: MediaItem) => {
  const dims = props.memoryKey ? unsealMeta(props.memoryKey, m) : null
  return galleryDisplayAspectRatio(dims?.w, dims?.h)
}
const layoutItems = computed(() => filteredMedia.value.map((m, index) => ({ id: m.mediaId, aspectRatio: aspectFor(m), media: m, index })))

// where you may move the selection: your own buckets other than the active one.
const moveTargets = computed<BucketRow[]>(() => bucketRows.value.filter((b) => b.canManage && !b.virtual && b.id !== activeBucket.value?.id))

// --- bucket actions ---------------------------------------------------------

function chooseBucket(id: string) { activeBucketId.value = id; selected.value = new Set() }

const bucketMenuItems = computed<MenuItem[]>(() => {
  const b = activeBucket.value
  return [
    { label: t('memory.buckets.upload'), icon: 'pi pi-upload', disabled: !b, command: () => emit('uploadBucket', b && !b.virtual ? b.id : null) },
    { label: t('memory.buckets.createBucket'), icon: 'pi pi-folder-plus', disabled: !b || !b.isPrimary || !b.canManage, command: openCreate },
    { label: t('memory.buckets.rename'), icon: 'pi pi-pencil', disabled: !b || b.isPrimary || !b.canManage, command: openRename },
    { label: t('memory.buckets.delete'), icon: 'pi pi-trash', disabled: !b || b.isPrimary || b.virtual || !b.canManage, command: openDeleteBucket },
  ]
})

function openCreate() {
  if (!activeBucket.value?.isPrimary || !activeBucket.value.canManage) return
  bucketDialog.value = { open: true, mode: 'create', name: '', saving: false, error: '' }
}
function openRename() {
  const b = activeBucket.value
  if (!b || b.isPrimary || !b.canManage) return
  bucketDialog.value = { open: true, mode: 'rename', name: b.displayName, saving: false, error: '' }
}
async function submitBucketDialog() {
  const key = props.memoryKey
  const name = bucketDialog.value.name.trim()
  if (!key || !name) return
  bucketDialog.value.saving = true
  bucketDialog.value.error = ''
  try {
    if (bucketDialog.value.mode === 'create') {
      await createBucket(props.scopeId, props.nodeId, key, name) // under your primary (server-side), materializing it
    } else if (activeBucket.value) {
      await renameBucket(props.scopeId, props.nodeId, key, activeBucket.value.id, name)
    }
    bucketDialog.value.open = false
    await loadBuckets()
    emit('changed')
  } catch {
    bucketDialog.value.error = t('memory.buckets.saveFailed')
  } finally {
    bucketDialog.value.saving = false
  }
}

function openDeleteBucket() {
  const b = activeBucket.value
  if (!b || b.isPrimary || b.virtual || !b.canManage) return
  deleteBucketDialog.value = { open: true, typed: '', saving: false, error: '' }
}
const canConfirmDeleteBucket = computed(() => !!activeBucket.value && deleteBucketDialog.value.typed === activeBucket.value.displayName)
async function confirmDeleteBucket() {
  const b = activeBucket.value
  if (!b || !canConfirmDeleteBucket.value) return
  deleteBucketDialog.value.saving = true
  try {
    await deleteBucket(props.scopeId, props.nodeId, b.id) // media reparent to your primary (server-side)
    deleteBucketDialog.value.open = false
    await loadBuckets()
    emit('changed')
  } catch {
    deleteBucketDialog.value.error = t('memory.buckets.saveFailed')
  } finally {
    deleteBucketDialog.value.saving = false
  }
}

// --- media selection + move + delete ---------------------------------------

function toggleSelect(id: string) {
  const next = new Set(selected.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selected.value = next
}
function selectAllVisible() { selected.value = new Set(filteredMedia.value.map((m) => m.mediaId)) }
function clearSelection() { selected.value = new Set() }
const selectedLabel = computed(() => t('memory.buckets.selected', { selected: selected.value.size, visible: filteredMedia.value.length }))

function openMediaMenu(event: Event, id: string) { menuMediaId.value = id; mediaMenu.value?.toggle(event) }
const mediaMenuItems = computed<MenuItem[]>(() => [
  { label: t('memory.buckets.moveMedia'), icon: 'pi pi-arrow-right-arrow-left', disabled: !moveTargets.value.length, command: () => { if (menuMediaId.value) { selected.value = new Set([menuMediaId.value]); openMove() } } },
  { label: t('memory.buckets.deleteMediaAction'), icon: 'pi pi-trash', command: () => { if (menuMediaId.value) { selected.value = new Set([menuMediaId.value]); openDeleteMedia() } } },
])
function toggleSelectionMenu(event: Event) { selectionMenu.value?.toggle(event) }
const selectionMenuItems = computed<MenuItem[]>(() => [
  { label: t('memory.buckets.moveTitle'), icon: 'pi pi-arrow-right-arrow-left', disabled: !moveTargets.value.length || !selected.value.size, command: openMove },
  { label: t('memory.buckets.deleteMediaAction'), icon: 'pi pi-trash', disabled: !selected.value.size, command: openDeleteMedia },
])

function openMove() {
  if (!selected.value.size || !moveTargets.value.length) return
  moveDialog.value = { open: true, target: moveTargets.value[0].id, saving: false, error: '' }
}
async function submitMove() {
  if (!moveDialog.value.target || !selected.value.size) return
  moveDialog.value.saving = true
  moveDialog.value.error = ''
  try {
    await moveBucketMedia(props.scopeId, props.nodeId, [...selected.value], moveDialog.value.target)
    moveDialog.value.open = false
    selected.value = new Set()
    await loadBuckets()
    emit('changed')
  } catch {
    moveDialog.value.error = t('memory.buckets.moveFailed')
  } finally {
    moveDialog.value.saving = false
  }
}

function openDeleteMedia() {
  if (!selected.value.size) return
  deleteMediaDialog.value = { open: true, typed: '', count: selected.value.size, saving: false }
}
const canConfirmDeleteMedia = computed(() => deleteMediaDialog.value.typed === String(deleteMediaDialog.value.count))
async function confirmDeleteMedia() {
  if (!canConfirmDeleteMedia.value) return
  deleteMediaDialog.value.saving = true
  try {
    await Promise.allSettled([...selected.value].map((id) => deleteMedia(props.scopeId, id))) // soft-delete → trash
    deleteMediaDialog.value.open = false
    selected.value = new Set()
    await loadBuckets()
    emit('changed')
  } finally {
    deleteMediaDialog.value.saving = false
  }
}

// --- upload / drag-drop into the active bucket ------------------------------

function onDrop(event: DragEvent) {
  dropActive.value = false
  const files = Array.from(event.dataTransfer?.files ?? []).filter((f) => f.type.startsWith('image/') || f.type.startsWith('video/'))
  if (!files.length || !activeBucket.value) return
  // reuse the parent's upload; the target bucket materializes on complete (null = your primary)
  emit('uploadBucket', activeBucket.value.virtual ? null : activeBucket.value.id)
}

function openTile(index: number) { emit('openLightbox', { items: filteredMedia.value, index }) }
</script>

<template>
  <div class="veyq-bucket-organizer">
    <div v-if="status === 'loading'" class="media-grid-placeholder">
      <span class="pi pi-spin pi-spinner" /><span>{{ t('media.loading') }}</span>
    </div>
    <p v-else-if="status === 'error'" class="veyq-empty">{{ t('media.unavailable') }}</p>

    <div
      v-else
      class="bucket-detail"
      :class="{ 'bucket-drop-active': dropActive }"
      @dragenter.prevent="dropActive = true"
      @dragover.prevent="dropActive = true"
      @dragleave.prevent="dropActive = false"
      @drop.prevent="onDrop"
    >
      <!-- picker + 3-dot bucket menu -->
      <div class="bucket-action-row">
        <Select
          class="bucket-select"
          :model-value="activeBucket?.id ?? ''"
          :options="selectOptions"
          option-label="displayName"
          option-value="value"
          :aria-label="t('memory.buckets.title')"
          @update:model-value="chooseBucket"
        >
          <template #value>
            <span v-if="activeBucket" class="bucket-select-value">
              <span class="pi" :class="activeBucket.isPrimary ? 'pi-user' : 'pi-folder'" />
              <span>{{ activeBucket.displayName }}</span>
              <small>{{ activeBucket.mediaCount }}</small>
            </span>
          </template>
          <template #option="{ option }">
            <span class="bucket-select-option" :style="{ paddingLeft: `${option.depth * 1.1}rem` }">
              <span v-if="option.depth > 0" class="bucket-select-branch" aria-hidden="true">↳</span>
              <span class="pi" :class="option.isPrimary ? 'pi-user' : 'pi-folder'" />
              <span class="bucket-select-name">{{ option.displayName }}</span>
              <small>{{ option.mediaCount }}</small>
            </span>
          </template>
        </Select>
        <button class="bucket-options-button" type="button" :aria-label="t('memory.buckets.options')" aria-haspopup="menu" @click="bucketMenu?.toggle($event)">
          <span class="pi pi-ellipsis-v" />
        </button>
        <Menu ref="bucketMenu" :model="bucketMenuItems" popup />
      </div>

      <!-- dropzone banner -->
      <div v-if="dropActive && activeBucket" class="bucket-dropzone">
        <span class="pi pi-cloud-upload" />
        <div>
          <strong>{{ t('memory.buckets.dropTitle', { bucket: activeBucket.displayName }) }}</strong>
          <small>{{ t('memory.buckets.dropCopy') }}</small>
        </div>
      </div>

      <!-- batch toolbar (own buckets only) -->
      <div v-if="activeBucket?.canManage && filteredMedia.length" class="bucket-selection-toolbar">
        <span class="bucket-selection-count">{{ selectedLabel }}</span>
        <button type="button" class="veyq-text-button" @click="selectAllVisible">{{ t('memory.buckets.selectAllVisible') }}</button>
        <button type="button" class="veyq-text-button" :disabled="!selected.size" @click="clearSelection">{{ t('memory.buckets.clearSelection') }}</button>
        <button class="bucket-options-button" type="button" :aria-label="t('memory.buckets.options')" :disabled="!selected.size" aria-haspopup="menu" @click="toggleSelectionMenu($event)">
          <span class="pi pi-ellipsis-v" />
        </button>
        <Menu ref="selectionMenu" :model="selectionMenuItems" popup />
      </div>

      <!-- the grid -->
      <p v-if="!filteredMedia.length" class="veyq-empty">{{ t('memory.buckets.emptyBucket') }}</p>
      <MediaGrid v-else :items="layoutItems" :aria-label="activeBucket?.displayName ?? t('memory.buckets.title')">
        <template #tile="{ tile, item }">
          <div
            class="gallery-tile bucket-tile"
            :class="{ 'is-selected': selected.has(item.media.mediaId) }"
            :style="{ transform: `translate(${tile.left}px, ${tile.top}px)`, width: `${tile.renderedWidth}px`, height: `${tile.renderedHeight}px` }"
          >
            <button
              class="bucket-tile-open"
              type="button"
              :aria-label="t('media.openItem', { index: item.index + 1 })"
              @click="openTile(item.index)"
            >
              <img
                v-if="thumbs.get(item.media.mediaId)"
                class="media-grid-tile-media media-grid-tile-media-loaded"
                :src="thumbs.get(item.media.mediaId)"
                alt=""
              />
              <span v-else-if="!hasDerivatives(item.media)" class="media-grid-icon pi pi-file" />
              <span v-else class="media-grid-tile-placeholder" />
            </button>
            <template v-if="activeBucket?.canManage">
              <label class="bucket-tile-check" :class="{ 'is-on': selected.has(item.media.mediaId) }">
                <input type="checkbox" :checked="selected.has(item.media.mediaId)" :aria-label="t('media.openItem', { index: item.index + 1 })" @click.stop @change="toggleSelect(item.media.mediaId)" />
                <span class="pi pi-check" aria-hidden="true" />
              </label>
              <button type="button" class="bucket-tile-menu" :aria-label="t('memory.buckets.options')" @click.stop="openMediaMenu($event, item.media.mediaId)">
                <span class="pi pi-ellipsis-v" />
              </button>
            </template>
          </div>
        </template>
      </MediaGrid>
      <Menu ref="mediaMenu" :model="mediaMenuItems" popup />
    </div>

    <!-- create / rename dialog -->
    <Dialog v-model:visible="bucketDialog.open" :header="bucketDialog.mode === 'create' ? t('memory.buckets.bucketDialogCreate') : t('memory.buckets.bucketDialogRename')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="submitBucketDialog">
        <label for="bucket-name">{{ t('memory.buckets.bucketName') }}</label>
        <input id="bucket-name" v-model="bucketDialog.name" type="text" autocomplete="off" />
        <p v-if="bucketDialog.error" class="veyq-form-error">{{ bucketDialog.error }}</p>
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="bucketDialog.saving" @click="bucketDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-primary" type="submit" :disabled="!bucketDialog.name.trim() || bucketDialog.saving">
            <span class="pi pi-save" /><span>{{ t('common.save') }}</span>
          </button>
        </div>
      </form>
    </Dialog>

    <!-- delete-bucket dialog (type-the-name) -->
    <Dialog v-model:visible="deleteBucketDialog.open" :header="t('memory.buckets.deleteBucketTitle')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="confirmDeleteBucket">
        <p class="bucket-dialog-copy">{{ t('memory.buckets.deleteBucketCopy', { bucket: activeBucket?.displayName }) }}</p>
        <label for="bucket-del-name">{{ t('memory.buckets.deleteBucketNameLabel') }}</label>
        <input id="bucket-del-name" v-model="deleteBucketDialog.typed" type="text" autocomplete="off" :placeholder="activeBucket?.displayName" />
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="deleteBucketDialog.saving" @click="deleteBucketDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-danger" type="submit" :disabled="!canConfirmDeleteBucket || deleteBucketDialog.saving">
            <span class="pi pi-trash" /><span>{{ t('memory.buckets.deleteBucketConfirm') }}</span>
          </button>
        </div>
      </form>
    </Dialog>

    <!-- move dialog -->
    <Dialog v-model:visible="moveDialog.open" :header="t('memory.buckets.moveTitle')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="submitMove">
        <label for="bucket-move-target">{{ t('memory.buckets.moveTarget') }}</label>
        <p v-if="!moveTargets.length" class="bucket-dialog-copy">{{ t('memory.buckets.moveEmpty') }}</p>
        <select v-else id="bucket-move-target" v-model="moveDialog.target">
          <option v-for="b in moveTargets" :key="b.id" :value="b.id">{{ '— '.repeat(b.depth) }}{{ b.displayName }}</option>
        </select>
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="moveDialog.saving" @click="moveDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-primary" type="submit" :disabled="!moveDialog.target || moveDialog.saving">
            <span class="pi pi-arrow-right-arrow-left" /><span>{{ t('memory.buckets.move') }}</span>
          </button>
        </div>
      </form>
    </Dialog>

    <!-- delete-media (trash) dialog -->
    <Dialog v-model:visible="deleteMediaDialog.open" :header="t('memory.buckets.deleteMediaTitle')" modal class="veyq-dialog-panel">
      <form class="veyq-form bucket-dialog-form" @submit.prevent="confirmDeleteMedia">
        <p class="bucket-dialog-copy">{{ t('memory.buckets.deleteMediaCopy', { count: deleteMediaDialog.count }) }}</p>
        <input v-model="deleteMediaDialog.typed" type="text" autocomplete="off" :placeholder="String(deleteMediaDialog.count)" :aria-label="t('memory.buckets.deleteMediaConfirm')" />
        <div class="veyq-dialog-action-row">
          <button class="veyq-dialog-button veyq-dialog-button-secondary" type="button" :disabled="deleteMediaDialog.saving" @click="deleteMediaDialog.open = false">
            <span class="pi pi-times" /><span>{{ t('common.cancel') }}</span>
          </button>
          <button class="veyq-dialog-button veyq-dialog-button-danger" type="submit" :disabled="!canConfirmDeleteMedia || deleteMediaDialog.saving">
            <span class="pi pi-trash" /><span>{{ t('memory.buckets.deleteMediaConfirm') }}</span>
          </button>
        </div>
      </form>
    </Dialog>
  </div>
</template>

<style scoped>
.veyq-bucket-organizer { display: grid; gap: 1rem; }
.bucket-detail { display: grid; gap: 1rem; position: relative; }
.bucket-drop-active::after {
  content: ''; position: absolute; inset: -0.5rem; border: 2px dashed color-mix(in srgb, var(--app-accent) 60%, transparent);
  border-radius: 12px; pointer-events: none;
}
.bucket-action-row { display: grid; grid-template-columns: minmax(220px, 440px) 40px; gap: 0.5rem; align-items: center; }
.bucket-select { width: 100%; }
.bucket-select-value, .bucket-select-option { display: inline-flex; align-items: center; gap: 0.5rem; min-width: 0; }
.bucket-select-value small, .bucket-select-option small {
  margin-left: auto; padding: 0 0.4rem; border-radius: 999px; background: var(--app-control-bg); color: var(--app-muted);
  font-size: 0.72rem; font-weight: 700;
}
.bucket-select-option { width: 100%; }
.bucket-select-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.bucket-select-branch { color: var(--app-muted); }
.bucket-options-button {
  width: 40px; height: 40px; display: grid; place-items: center; border: 1px solid var(--app-line);
  border-radius: 8px; background: transparent; color: inherit; cursor: pointer;
}
.bucket-options-button:hover:not(:disabled) { background: var(--app-control-bg); }
.bucket-options-button:disabled { opacity: 0.5; cursor: default; }
.bucket-dropzone {
  display: grid; grid-template-columns: 40px 1fr; gap: 0.75rem; align-items: center; padding: 0.75rem 1rem;
  border: 1px dashed color-mix(in srgb, var(--app-accent) 55%, var(--app-line)); border-radius: 10px;
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
}
.bucket-dropzone strong { display: block; }
.bucket-dropzone small { color: var(--app-muted); }
.bucket-selection-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; }
.bucket-selection-count { margin-right: auto; color: var(--app-muted); font-size: 0.85rem; }
.bucket-tile { position: absolute; top: 0; left: 0; overflow: hidden; border-radius: var(--app-radius-sm, 8px); }
.bucket-tile.is-selected { outline: 2px solid var(--app-accent); outline-offset: -2px; }
.bucket-tile-open { position: absolute; inset: 0; width: 100%; height: 100%; padding: 0; border: 0; background: var(--app-control-bg); cursor: pointer; }
.bucket-tile-check {
  position: absolute; top: 6px; right: 6px; width: 22px; height: 22px; display: grid; place-items: center;
  border-radius: 999px; background: color-mix(in srgb, #000 45%, transparent); color: #fff; cursor: pointer;
}
.bucket-tile-check.is-on { background: var(--app-accent); }
.bucket-tile-check input { position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.bucket-tile-check .pi { font-size: 0.7rem; opacity: 0; }
.bucket-tile-check.is-on .pi { opacity: 1; }
.bucket-tile-menu {
  position: absolute; bottom: 6px; right: 6px; width: 26px; height: 26px; display: grid; place-items: center;
  border: 0; border-radius: 999px; background: color-mix(in srgb, #000 45%, transparent); color: #fff; cursor: pointer;
}
.bucket-dialog-form { display: grid; gap: 0.7rem; }
.bucket-dialog-copy { color: var(--app-muted); margin: 0; }
.bucket-dialog-form input[type='text'], .bucket-dialog-form select {
  width: 100%; padding: 0.5rem 0.65rem; border: 1px solid var(--app-line); border-radius: 8px;
  background: var(--app-surface, transparent); color: inherit; font: inherit;
}
.veyq-dialog-button-danger { background: var(--app-danger, #e11d48); color: #fff; }
@media (max-width: 760px) { .bucket-action-row { grid-template-columns: minmax(0, 1fr) 40px; } }
</style>
