<script setup lang="ts">
import type { OwnerBadge } from '../../api/explorerClient'

defineProps<{
  ownerBadge: OwnerBadge
}>()

function badgeIcon(ownerBadge: OwnerBadge): string {
  if (ownerBadge.kind === 'GROUP') return 'pi pi-users'
  if (ownerBadge.kind === 'USER') return 'pi pi-user'
  return 'pi pi-building'
}
</script>

<template>
  <span class="explorer-scope-badge">
    <span :class="badgeIcon(ownerBadge)" />
    {{ ownerBadge.displayName }}
  </span>
</template>
