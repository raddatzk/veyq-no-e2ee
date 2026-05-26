import { ref } from 'vue'

export const memoryFavoritesRefreshToken = ref(0)

export function notifyMemoryFavoritesChanged() {
  memoryFavoritesRefreshToken.value += 1
}
