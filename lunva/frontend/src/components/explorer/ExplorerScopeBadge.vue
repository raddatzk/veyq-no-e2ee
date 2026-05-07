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

function badgeLabel(ownerBadge: OwnerBadge): string {
  if (ownerBadge.kind === 'GROUP') return `@${ownerBadge.displayName}`
  if (ownerBadge.kind === 'USER') return `~${ownerBadge.displayName}`
  return ownerBadge.displayName
}
</script>

<template>
  <span class="explorer-scope-badge">
    <span :class="badgeIcon(ownerBadge)" />
    {{ badgeLabel(ownerBadge) }}
  </span>
</template>
