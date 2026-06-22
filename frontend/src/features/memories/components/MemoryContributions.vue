<script setup lang="ts">
// Contributions tab (per-contributor media buckets, ADR 0023) on the E2EE backend. Bucket names are sealed
// under the scope content key; the caller's own primary bucket is synthesized server-side (no sealed name) so
// the client labels it with the caller's own display name. Sub-buckets / guest primaries carry sealed names.
// Create / rename / delete are own-bucket only — the server enforces it (NOT_YOUR_BUCKET); the client gates
// the actions optimistically by comparing ownerKey (`user:<uid>`) to the unlocked user's own.
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import Menu from 'primevue/menu'
import Select from 'primevue/select'
import Message from 'primevue/message'
import type { MenuItem } from 'primevue/menuitem'
import { loadGalleryTiles, loadPreview, loadOriginal } from '../../gallery/gallery'
import { useGallery } from '../../gallery/useGallery'
import { calculateJustifiedLayout } from '../../gallery/justifiedLayout'
import { setCover } from '../../memory/curate/curate'
import {
  listBuckets, createBucket, renameBucket, deleteBucket, bucketName, type Bucket,
} from '../../memory/buckets/buckets'
import { requireSession, session } from '../../../app/session'
import type { ActiveSpace } from '../../../features/home/home'

const props = defineProps<{ active: ActiveSpace; nodeId: string }>()
const { locale, t } = useI18n()

const buckets = ref<Bucket[]>([])
const activeBucketId = ref('')
const activeCameraKey = ref('')
const error = ref('')
const coverMsg = ref('')

const bucketActionMenu = ref<InstanceType<typeof Menu> | null>(null)
const bucketDialogMode = ref<'create' | 'rename'>('create')
const isBucketDialogOpen = ref(false)
const isDeleteBucketOpen = ref(false)
const bucketNameInput = ref('')
const busy = ref(false)

const myOwnerKey = computed(() => `user:${session.unlocked?.user.id ?? ''}`)

const {
  tiles, galleryEl, containerWidth, tileById, lightbox, lightboxTile, downloading,
  observe, revokeTiles, openLightbox, closeLightbox, downloadThisOriginal,
} = useGallery({
  loadPreview: (tile) => loadPreview(session.apiBase, requireSession(), props.active, tile),
  loadOriginal: (tile) => loadOriginal(session.apiBase, requireSession(), props.active, tile),
})

/** Decrypt a bucket's label: own primary → own display name; named buckets → sealed name; else a fallback. */
function labelFor(bucket: Bucket): string {
  const name = bucketName(props.active.contentKey, bucket)
  if (name) return name
  if (bucket.primary && bucket.ownerKey === myOwnerKey.value) {
    return session.unlocked?.user.displayName || t('homeView.title')
  }
  return t('media.unknownUploader')
}

const isOwn = (bucket: Bucket) => bucket.ownerKey === myOwnerKey.value

const bucketOptions = computed(() =>
  [...buckets.value]
    .sort((a, b) => Number(b.primary) - Number(a.primary) || labelFor(a).localeCompare(labelFor(b), locale.value))
    .map((bucket) => ({
      value: bucket.id,
      displayName: labelFor(bucket),
      label: `${labelFor(bucket)} (${bucket.mediaCount})`,
      primary: bucket.primary,
      mediaCount: bucket.mediaCount,
    })),
)

const activeBucket = computed(
  () => buckets.value.find((b) => b.id === activeBucketId.value) ?? buckets.value[0] ?? null,
)

// Media in the active bucket. The synthesized own primary has no persisted id; its media carry bucketId=undefined.
const bucketTiles = computed(() => {
  const bucket = activeBucket.value
  if (!bucket) return []
  const isSynthPrimary = bucket.primary && bucket.id.startsWith('primary:')
  return tiles.value.filter((tile) => (isSynthPrimary ? !tile.bucketId : tile.bucketId === bucket.id))
})

type CameraChip = { key: string; label: string; count: number }
const cameraChips = computed<CameraChip[]>(() => {
  const map = new Map<string, CameraChip>()
  for (const tile of bucketTiles.value) {
    const label = [tile.cameraMake, tile.cameraModel].filter(Boolean).join(' ').trim()
    const key = label ? label.toLocaleLowerCase() : 'unknown'
    const existing = map.get(key)
    if (existing) existing.count += 1
    else map.set(key, { key, label: label || t('media.unknownCamera'), count: 1 })
  }
  return [...map.values()].sort((a, b) => a.label.localeCompare(b.label, locale.value))
})

const filteredTiles = computed(() => {
  if (!activeCameraKey.value) return bucketTiles.value
  return bucketTiles.value.filter((tile) => {
    const label = [tile.cameraMake, tile.cameraModel].filter(Boolean).join(' ').trim()
    return (label ? label.toLocaleLowerCase() : 'unknown') === activeCameraKey.value
  })
})

const layout = computed(() =>
  calculateJustifiedLayout({
    items: filteredTiles.value.map((tile) => ({ id: tile.id, aspectRatio: tile.aspectRatio })),
    containerWidth: containerWidth.value,
    targetHeight: containerWidth.value <= 720 ? 150 : 200,
    gap: 8,
  }),
)

const canCreateSub = computed(() => Boolean(activeBucket.value?.primary && activeBucket.value && isOwn(activeBucket.value)))
const canManageActive = computed(() => Boolean(activeBucket.value && !activeBucket.value.primary && isOwn(activeBucket.value)))

const bucketActionItems = computed<MenuItem[]>(() => [
  { label: t('media.createBucket'), icon: 'pi pi-folder-plus', disabled: !canCreateSub.value, command: openCreateBucket },
  { label: t('common.edit'), icon: 'pi pi-pencil', disabled: !canManageActive.value, command: openRenameBucket },
  { label: t('common.delete'), icon: 'pi pi-trash', disabled: !canManageActive.value, command: () => { isDeleteBucketOpen.value = true } },
])

onMounted(reload)
watch(() => props.nodeId, reload)
watch(cameraChips, (chips) => {
  if (activeCameraKey.value && !chips.some((c) => c.key === activeCameraKey.value)) activeCameraKey.value = ''
})

async function reload() {
  error.value = ''
  try {
    buckets.value = await listBuckets(session.apiBase, requireSession(), props.active, props.nodeId)
    if (!buckets.value.some((b) => b.id === activeBucketId.value)) activeBucketId.value = buckets.value[0]?.id ?? ''
    revokeTiles()
    tiles.value = await loadGalleryTiles(session.apiBase, requireSession(), props.active, props.nodeId)
    void ensureWidth()
  } catch {
    error.value = t('memory.loadFailed')
  }
}

/** Seed the layout width, retrying across frames until the tab panel is laid out (the shared ResizeObserver
 *  does not deliver in the headless preview, and a non-default tab panel can mount at width 0). */
async function ensureWidth(tries = 12) {
  await nextTick()
  observe()
  if (!containerWidth.value && tries > 0) requestAnimationFrame(() => void ensureWidth(tries - 1))
}

function chooseBucket(id: string) {
  activeBucketId.value = id
  activeCameraKey.value = ''
}

function toggleBucketMenu(event: MouseEvent) {
  bucketActionMenu.value?.toggle(event)
}

function openCreateBucket() {
  bucketDialogMode.value = 'create'
  bucketNameInput.value = ''
  error.value = ''
  isBucketDialogOpen.value = true
}

function openRenameBucket() {
  if (!activeBucket.value) return
  bucketDialogMode.value = 'rename'
  bucketNameInput.value = labelFor(activeBucket.value)
  error.value = ''
  isBucketDialogOpen.value = true
}

async function submitBucketDialog() {
  const name = bucketNameInput.value.trim()
  if (!name || !activeBucket.value) return
  busy.value = true
  error.value = ''
  try {
    if (bucketDialogMode.value === 'create') {
      const created = await createBucket(session.apiBase, requireSession(), props.active, props.nodeId, name)
      activeBucketId.value = created.id
    } else {
      await renameBucket(session.apiBase, requireSession(), props.active, props.nodeId, activeBucket.value.id, name)
    }
    isBucketDialogOpen.value = false
    await reload()
  } catch {
    error.value = bucketDialogMode.value === 'create' ? t('media.bucketSaveFailed') : t('media.bucketSaveFailed')
  } finally {
    busy.value = false
  }
}

async function confirmDeleteBucket() {
  if (!activeBucket.value) return
  busy.value = true
  error.value = ''
  try {
    await deleteBucket(session.apiBase, requireSession(), props.active, props.nodeId, activeBucket.value.id)
    isDeleteBucketOpen.value = false
    activeBucketId.value = ''
    await reload()
  } catch {
    error.value = t('media.bucketDeleteFailed')
  } finally {
    busy.value = false
  }
}

async function setAsCover() {
  if (!lightboxTile.value) return
  coverMsg.value = ''
  try {
    await setCover(session.apiBase, requireSession(), props.active, props.nodeId, lightboxTile.value.id)
    coverMsg.value = t('memoryDetail.coverSet')
    closeLightbox()
  } catch {
    coverMsg.value = ''
  }
}
</script>

<template>
  <div ref="galleryEl" class="memory-contributions">
    <Message v-if="coverMsg" severity="success" :closable="false">{{ coverMsg }}</Message>
    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <p v-if="!buckets.length" class="gallery-empty">{{ t('media.empty') }}</p>

    <template v-else>
      <div class="contrib-action-row">
        <Select
          class="contrib-bucket-select"
          :model-value="activeBucketId"
          :options="bucketOptions"
          option-label="label"
          option-value="value"
          :aria-label="t('media.uploaderBuckets')"
          @update:model-value="chooseBucket"
        >
          <template #value>
            <span v-if="activeBucket" class="contrib-bucket-value">
              <span class="pi" :class="activeBucket.primary ? 'pi-user' : 'pi-folder'" />
              <span>{{ labelFor(activeBucket) }} ({{ activeBucket.mediaCount }})</span>
            </span>
          </template>
          <template #option="{ option }">
            <span class="contrib-bucket-option">
              <span class="pi" :class="option.primary ? 'pi-user' : 'pi-folder'" />
              <span>{{ option.displayName }}</span>
              <small>{{ option.mediaCount }}</small>
            </span>
          </template>
        </Select>
        <Button
          type="button"
          class="veyq-icon-button"
          icon="pi pi-ellipsis-v"
          :aria-label="t('media.options')"
          text
          @click="toggleBucketMenu"
        />
        <Menu ref="bucketActionMenu" :model="bucketActionItems" popup />
      </div>

      <div v-if="cameraChips.length" class="contrib-camera-filter">
        <button
          type="button"
          class="contrib-camera-chip"
          :class="{ 'contrib-camera-chip-active': !activeCameraKey }"
          @click="activeCameraKey = ''"
        >
          {{ t('media.allCameras') }} <span>{{ bucketTiles.length }}</span>
        </button>
        <button
          v-for="camera in cameraChips"
          :key="camera.key"
          type="button"
          class="contrib-camera-chip"
          :class="{ 'contrib-camera-chip-active': camera.key === activeCameraKey }"
          @click="activeCameraKey = camera.key"
        >
          {{ camera.label }} <span>{{ camera.count }}</span>
        </button>
      </div>

      <p v-if="!filteredTiles.length" class="gallery-empty">{{ t('media.empty') }}</p>

      <div v-else class="gallery-rows" :style="{ height: layout.height + 'px' }">
        <button
          v-for="tile in layout.tiles"
          :key="tile.id"
          type="button"
          class="gallery-tile"
          :style="{ left: tile.left + 'px', top: tile.top + 'px', width: tile.renderedWidth + 'px', height: tile.renderedHeight + 'px' }"
          @click="openLightbox(tileById[tile.id])"
        >
          <img :src="tileById[tile.id]?.url" alt="" loading="lazy" />
        </button>
      </div>
    </template>

    <!-- Lightbox -->
    <div v-if="lightbox" class="gallery-lightbox" @click.self="closeLightbox()">
      <div class="gallery-lightbox-inner">
        <img :src="lightbox" alt="" />
        <div class="gallery-lightbox-actions">
          <Button type="button" icon="pi pi-image" :label="t('memoryDetail.setCover')" severity="secondary" text @click="setAsCover()" />
          <Button type="button" icon="pi pi-download" :label="t('gallery.download')" :loading="downloading" severity="secondary" @click="downloadThisOriginal()" />
          <Button type="button" icon="pi pi-times" :label="t('common.close')" text @click="closeLightbox()" />
        </div>
      </div>
    </div>

    <Dialog
      v-model:visible="isBucketDialogOpen"
      :header="bucketDialogMode === 'create' ? t('media.createBucket') : t('media.bucketDialogTitle')"
      modal
      class="tenant-dialog"
    >
      <form class="veyq-form" @submit.prevent="submitBucketDialog">
        <label>{{ t('media.bucketName') }}<InputText v-model="bucketNameInput" autofocus /></label>
        <div class="veyq-dialog-action-row">
          <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="isBucketDialogOpen = false" />
          <Button type="submit" :label="t('common.save')" icon="pi pi-save" :loading="busy" :disabled="!bucketNameInput.trim()" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="isDeleteBucketOpen" :header="t('media.deleteBucketTitle')" modal class="tenant-dialog">
      <p>{{ t('media.deleteBucketCopy', { bucket: activeBucket ? labelFor(activeBucket) : '' }) }}</p>
      <div class="veyq-dialog-action-row">
        <Button type="button" :label="t('common.cancel')" severity="secondary" text @click="isDeleteBucketOpen = false" />
        <Button type="button" :label="t('media.deleteBucketConfirmAction')" icon="pi pi-trash" severity="danger" :loading="busy" @click="confirmDeleteBucket" />
      </div>
    </Dialog>
  </div>
</template>

<style scoped>
.memory-contributions { margin-bottom: 1.5rem; }
.gallery-empty { opacity: 0.55; font-size: 0.9rem; }
.contrib-action-row { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.75rem; }
.contrib-bucket-select { min-width: 16rem; }
.contrib-bucket-value, .contrib-bucket-option { display: inline-flex; align-items: center; gap: 0.5rem; }
.contrib-bucket-option small { margin-left: auto; opacity: 0.6; }
.contrib-camera-filter { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-bottom: 0.9rem; }
.contrib-camera-chip {
  display: inline-flex; align-items: center; gap: 0.4rem;
  padding: 0.3rem 0.7rem; border-radius: 999px; cursor: pointer;
  border: 1px solid var(--p-content-border-color, rgba(127, 127, 127, 0.25));
  background: transparent; color: inherit; font-size: 0.85rem;
}
.contrib-camera-chip span { opacity: 0.6; }
.contrib-camera-chip-active { border-color: var(--p-primary-color, #5b4df5); color: var(--p-primary-color, #5b4df5); }
.gallery-rows { position: relative; width: 100%; }
.gallery-tile {
  position: absolute; padding: 0; border: none; border-radius: 8px; overflow: hidden; cursor: pointer;
  background: var(--p-content-border-color, rgba(127, 127, 127, 0.15));
}
.gallery-tile img { width: 100%; height: 100%; object-fit: cover; display: block; }
.gallery-lightbox {
  position: fixed; inset: 0; z-index: 1000;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, 0.85); padding: 2rem;
}
.gallery-lightbox-inner { display: flex; flex-direction: column; align-items: center; gap: 0.75rem; max-width: 92vw; max-height: 92vh; }
.gallery-lightbox-inner img { max-width: 92vw; max-height: 80vh; border-radius: 8px; object-fit: contain; }
.gallery-lightbox-actions { display: flex; gap: 0.5rem; }
</style>
