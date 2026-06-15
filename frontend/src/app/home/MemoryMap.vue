<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import 'leaflet/dist/leaflet.css'

const props = defineProps<{
  points: { id: string; lat: number; lng: number }[]
  tracks?: { id: string; name: string; points: { lat: number; lng: number }[] }[]
}>()
const emit = defineEmits<{ select: [id: string] }>()

const el = ref<HTMLElement | null>(null)
let map: L.Map | null = null
let layer: L.LayerGroup | null = null

function render() {
  if (!map) return
  layer?.remove()
  layer = L.layerGroup().addTo(map)
  const latlngs: L.LatLngTuple[] = []
  for (const t of props.tracks ?? []) {
    const line = t.points.map((p) => [p.lat, p.lng] as L.LatLngTuple)
    if (line.length > 1) L.polyline(line, { color: '#9b8cff', weight: 4, opacity: 0.85 }).bindTooltip(t.name).addTo(layer)
    latlngs.push(...line)
  }
  for (const p of props.points) {
    latlngs.push([p.lat, p.lng])
    L.circleMarker([p.lat, p.lng], { radius: 7, color: '#fff', weight: 2, fillColor: '#9b8cff', fillOpacity: 1 })
      .on('click', () => emit('select', p.id))
      .addTo(layer)
  }
  if (latlngs.length === 1) map.setView(latlngs[0], 13)
  else if (latlngs.length > 1) map.fitBounds(L.latLngBounds(latlngs).pad(0.2))
}

onMounted(() => {
  map = L.map(el.value!, { attributionControl: true }).setView([20, 0], 2)
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '© OpenStreetMap',
  }).addTo(map)
  render()
})

watch([() => props.points, () => props.tracks], render, { deep: true })
onBeforeUnmount(() => { map?.remove(); map = null })
</script>

<template>
  <div ref="el" class="memory-map"></div>
</template>
