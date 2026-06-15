<script setup lang="ts">
// A lightweight camera overlay that decodes a QR and emits its text. jsqr is imported lazily so the
// decoder only loads when the user actually opens the scanner.
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { t } from '../../i18n/i18n'

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
      <p class="auth-sub" style="margin: 0.6rem 0 0">{{ t('kit.scanHint') }}</p>
      <div v-if="error" class="auth-msg auth-msg-error">{{ error }}</div>
      <button class="btn btn-ghost" type="button" style="width: 100%; margin-top: 0.6rem" @click="emit('close')">{{ t('common.cancel') }}</button>
    </div>
  </div>
</template>
