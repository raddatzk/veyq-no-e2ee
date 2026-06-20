// A small global upload queue so progress survives navigation and is surfaced in one place (the topbar
// status menu) regardless of which Memory triggered it. The actual sealing/upload stays in uploads/media.ts;
// this only tracks per-file status (queued → uploading → done/error) and runs the supplied task sequentially.
import { computed, reactive } from 'vue'

export type UploadStatus = 'queued' | 'uploading' | 'done' | 'error'
export type UploadItem = { id: string; name: string; status: UploadStatus; error?: string }

const state = reactive({ items: [] as UploadItem[], seq: 0 })

export const uploadItems = computed(() => state.items)
export const activeUploadCount = computed(
  () => state.items.filter((i) => i.status === 'queued' || i.status === 'uploading').length,
)
export const hasUploads = computed(() => state.items.length > 0)

/**
 * Enqueue files and run `task` for each, sequentially, tracking per-file status. Always resolves (per-file
 * failures are recorded on the item, not thrown) so one bad file never aborts the rest of the batch.
 */
export async function enqueueUploads(files: File[], task: (file: File) => Promise<void>): Promise<void> {
  const batch = files.map((file) => {
    const item: UploadItem = { id: `u${(state.seq += 1)}`, name: file.name, status: 'queued' }
    state.items.push(item)
    return { item, file }
  })
  for (const { item, file } of batch) {
    item.status = 'uploading'
    try {
      await task(file)
      item.status = 'done'
    } catch (e) {
      item.status = 'error'
      item.error = e instanceof Error ? e.message : String(e)
    }
  }
}

/** Drop finished (done/error) items, keeping anything still in flight. */
export function clearFinishedUploads(): void {
  state.items = state.items.filter((i) => i.status === 'queued' || i.status === 'uploading')
}
