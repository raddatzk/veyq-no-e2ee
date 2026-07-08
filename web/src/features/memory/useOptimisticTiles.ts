// uc.memory.upload (web, cr-0074 + cr-0101) — the optimistic in-grid upload-tile lifecycle, shared by the
// internal gallery (MemoryDetail) and the anonymous contributor viewer (ShareView). The global upload queue's
// still-in-flight files for a given grouping `key` (a Memory node id, or a shareId) render immediately as grid
// tiles: an instant local preview (a `URL.createObjectURL` of the user's own file — never uploaded in the clear)
// + a per-tile progress / failed-with-retry state, pinned ahead of the listed media. Each reconciles into the
// real decrypted thumbnail once its media lists (matched by the `mediaId` the queue records on `done`). The pure
// selection/merge logic lives in ./optimisticUpload; this composable owns the reactive preview lifecycle.
import { reactive, computed, watch, onBeforeUnmount, type Ref } from 'vue'
import { createBrowserDeriver } from './media'
import { galleryDisplayAspectRatio } from './mediaGridLayout'
import { useUploadQueue, type UploadFile } from './uploadQueue'
import { selectOptimisticUploads, optimisticTileId } from './optimisticUpload'

/** The view-model of one optimistic tile — everything the grid + [OptimisticTile] need to render it. */
export type OptimisticTileVM = {
  /** The id it carries in the justified-layout input (namespaced so it can't collide with a real media id). */
  layoutId: string
  fileId: string
  /** The media id the upload settled to (cr-0102 delete-own targets it); undefined until `done`. */
  mediaId?: string
  name: string
  url: string | null
  aspectRatio: number
  status: UploadFile['status']
  progress: number
  isVideo: boolean
}

/**
 * Reactive optimistic tiles for a grouping `key` (the Memory node id, or a shareId), reconciled against the
 * currently-listed media ids. Owns the local preview object URLs (mint on appear, revoke on drop / unmount).
 * `donorPreviewFor` hands a just-listed media's still-live preview to the caller for a seamless thumbnail swap.
 */
export function useOptimisticTiles(key: Ref<string>, listedMediaIds: Ref<ReadonlySet<string>>) {
  const { batches } = useUploadQueue()
  const deriver = createBrowserDeriver()
  type Preview = { url: string; aspectRatio: number; donated: boolean }
  const previews = reactive(new Map<string, Preview>())

  const optimisticFiles = computed(() => selectOptimisticUploads(batches.value, key.value, listedMediaIds.value))
  const optimisticTiles = computed<OptimisticTileVM[]>(() =>
    optimisticFiles.value.map((f) => {
      const p = previews.get(f.id)
      return {
        layoutId: optimisticTileId(f.id),
        fileId: f.id,
        mediaId: f.mediaId,
        name: f.name,
        url: p?.url ?? null,
        aspectRatio: p?.aspectRatio ?? galleryDisplayAspectRatio(undefined, undefined),
        status: f.status,
        progress: f.progress,
        isVideo: f.mediaType.startsWith('video/'),
      }
    }),
  )

  // Own the preview lifecycle: mint a local objectURL + probe the aspect ratio as each file appears; revoke +
  // prune when it drops (cancelled / reconciled). A donated url is skipped — the caller's thumbs own its revoke.
  watch(
    optimisticFiles,
    (files) => {
      const present = new Set(files.map((f) => f.id))
      for (const [fileId, p] of previews) {
        if (present.has(fileId)) continue
        if (!p.donated) URL.revokeObjectURL(p.url)
        previews.delete(fileId)
      }
      for (const f of files) {
        if (previews.has(f.id)) continue
        previews.set(f.id, { url: URL.createObjectURL(f.file), aspectRatio: galleryDisplayAspectRatio(undefined, undefined), donated: false })
        void probeAspectRatio(f)
      }
    },
    { immediate: true },
  )

  async function probeAspectRatio(f: UploadFile) {
    try {
      const dims = await deriver.probeDimensions?.(f.file, f.mediaType)
      const p = previews.get(f.id)
      if (p && dims) previews.set(f.id, { ...p, aspectRatio: galleryDisplayAspectRatio(dims.w, dims.h) })
    } catch {
      // best-effort: keep the fallback aspect ratio
    }
  }

  /** A just-listed media's still-live preview url, marked donated (its revoke passes to the caller's thumbs). */
  function donorPreviewFor(mediaId: string): string | null {
    for (const b of batches.value) {
      for (const f of b.files) {
        if (f.status !== 'done' || f.mediaId !== mediaId) continue
        const p = previews.get(f.id)
        if (p && !p.donated) {
          previews.set(f.id, { ...p, donated: true })
          return p.url
        }
      }
    }
    return null
  }

  onBeforeUnmount(() => {
    for (const p of previews.values()) if (!p.donated) URL.revokeObjectURL(p.url) // donated urls are owned by the caller's thumbs
    previews.clear()
  })

  return { optimisticTiles, donorPreviewFor }
}
