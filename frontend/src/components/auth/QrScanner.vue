<script setup lang="ts">
// A lightweight camera overlay that decodes a QR and emits its text. jsqr is imported lazily so the
// decoder only loads when the user actually opens the scanner. Used to import an Emergency Kit by scanning.
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const emit = defineEmits<{ decoded: [text: string]; close: [] }>()
const video = ref<HTMLVideoElement | null>(null)
const error = ref('')
let stream: MediaStream | null = null
let raf = 0
let stopped = false

onMounted(async () => {
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
    const el = video.value!
    el.srcObject = stream
    await el.play()
    const jsQR = (await import('jsqr')).default
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d', { willReadFrequently: true })!
    const tick = () => {
      if (stopped) return
      if (el.readyState === el.HAVE_ENOUGH_DATA) {
        canvas.width = el.videoWidth
        canvas.height = el.videoHeight
        ctx.drawImage(el, 0, 0, canvas.width, canvas.height)
        const img = ctx.getImageData(0, 0, canvas.width, canvas.height)
        const code = jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' })
        if (code?.data) { emit('decoded', code.data); return }
      }
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
  } catch {
    error.value = t('kit.scanFailed')
  }
})

onBeforeUnmount(() => {
  stopped = true
  cancelAnimationFrame(raf)
  stream?.getTracks().forEach((tr) => tr.stop())
})
</script>

<template>
  <div class="qr-scan-overlay" @click.self="emit('close')">
    <div class="qr-scan-card">
      <video ref="video" class="qr-scan-video" playsinline muted></video>
      <p class="qr-scan-hint">{{ t('kit.scanHint') }}</p>
      <div v-if="error" class="qr-scan-error">{{ error }}</div>
      <button class="qr-scan-cancel" type="button" @click="emit('close')">{{ t('common.cancel') }}</button>
    </div>
  </div>
</template>

<style scoped>
.qr-scan-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.7);
}
.qr-scan-card {
  background: var(--p-content-background, #1e1e1e);
  border-radius: 12px;
  padding: 1rem;
  width: min(92vw, 420px);
}
.qr-scan-video {
  width: 100%;
  border-radius: 8px;
  background: #000;
  aspect-ratio: 1;
  object-fit: cover;
}
.qr-scan-hint {
  margin: 0.6rem 0 0;
  font-size: 0.9rem;
  opacity: 0.8;
}
.qr-scan-error {
  margin-top: 0.6rem;
  color: var(--p-red-400, #f87171);
}
.qr-scan-cancel {
  width: 100%;
  margin-top: 0.6rem;
  padding: 0.5rem;
  border: 1px solid var(--p-content-border-color, #444);
  border-radius: 8px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
</style>
