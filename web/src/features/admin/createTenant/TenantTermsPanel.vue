<script setup lang="ts">
// uc.admin.tenant-terms (web, cr-0114, ADR 0035) — the "Terms" rubric of the tenant settings. The tenant OWNER
// sets the server's terms as per-locale (DE+EN) markdown; a normalized change bumps the version server-side,
// re-gating members (uc.account.accept-terms). Member-readable → non-owners see it read-only; the editor + Save
// are owner-only (the backend 403s otherwise). Operator-blind: terms are the operator's PUBLIC policy, no key.
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { getTenantTerms, updateTenantTerms, tenantError } from './tenants'
import { TERMS_TEMPLATE } from './termsTemplate'
import { listMyTenants } from '../../spaces/scopes'
import { activeSpace } from '../../../app/activeSpace'

const { t } = useI18n()
const router = useRouter()

const de = ref('')
const en = ref('')
const version = ref(0)
const isOwner = ref(false)
const loading = ref(true)
const loadError = ref<'' | 'failed'>('')
const notice = ref('')
const actionError = ref('')
const saving = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  if (!activeSpace.tenantId) {
    loading.value = false
    loadError.value = 'failed'
    return
  }
  try {
    const terms = await getTenantTerms(activeSpace.tenantId)
    version.value = terms.termsVersion
    de.value = terms.terms?.de ?? ''
    en.value = terms.terms?.en ?? ''
    const mine = (await listMyTenants()).find((tn) => tn.tenantId === activeSpace.tenantId)
    isOwner.value = mine?.role === 'OWNER'
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    loadError.value = 'failed'
  } finally {
    loading.value = false
  }
}

/** Seed an empty locale from the clause template (never clobbers text the owner already wrote). */
function insertTemplate() {
  if (!de.value.trim()) de.value = TERMS_TEMPLATE.de
  if (!en.value.trim()) en.value = TERMS_TEMPLATE.en
}

async function save() {
  actionError.value = ''
  notice.value = ''
  saving.value = true
  try {
    const updated = await updateTenantTerms(activeSpace.tenantId!, { de: de.value, en: en.value })
    version.value = updated.termsVersion
    de.value = updated.terms?.de ?? ''
    en.value = updated.terms?.en ?? ''
    notice.value = t('terms.saved', { version: updated.termsVersion })
  } catch (e) {
    const { status } = await tenantError(e)
    if (status === 401) return void router.push({ name: 'login' })
    actionError.value = status === 403 ? t('terms.notOwner') : t('terms.saveFailed')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <section class="settings-form">
      <div class="settings-section-header">
        <div>
          <h2>{{ t('terms.navTitle') }}</h2>
          <p>{{ t('terms.editorSub') }}</p>
        </div>
        <span v-if="version > 0" class="veyq-badge">{{ t('terms.version', { version }) }}</span>
      </div>

      <p v-if="loading" class="settings-muted">{{ t('terms.loading') }}</p>
      <Message v-else-if="loadError" severity="error" :closable="false">{{ t('terms.loadFailed') }}</Message>

      <template v-else>
        <Message v-if="!isOwner" severity="info" :closable="false">{{ t('terms.readonlyNote') }}</Message>
        <Message v-if="notice" severity="success" :closable="false">{{ notice }}</Message>
        <Message v-if="actionError" severity="error" :closable="false">{{ actionError }}</Message>

        <div class="locale-markdown-editor">
          <div class="locale-markdown-editor-entry">
            <span>{{ t('terms.localeDe') }}</span>
            <textarea v-model="de" :readonly="!isOwner" rows="12" :placeholder="t('terms.placeholder')"></textarea>
          </div>
          <div class="locale-markdown-editor-entry">
            <span>{{ t('terms.localeEn') }}</span>
            <textarea v-model="en" :readonly="!isOwner" rows="12" :placeholder="t('terms.placeholder')"></textarea>
          </div>
        </div>

        <template v-if="isOwner">
          <small class="settings-muted">{{ t('terms.markdownHint') }}</small>
          <div class="terms-editor-actions">
            <Button :label="t('terms.save')" icon="pi pi-save" :loading="saving" @click="save" />
            <Button
              :label="t('terms.insertTemplate')"
              icon="pi pi-file-plus"
              severity="secondary"
              outlined
              :disabled="saving"
              @click="insertTemplate"
            />
          </div>
        </template>
      </template>
    </section>
  </section>
</template>

<style scoped>
.settings-muted {
  margin: 0;
  color: var(--app-muted);
}
.terms-editor-actions {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 0.5rem;
}
.locale-markdown-editor-entry textarea {
  min-height: 14rem;
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.85em;
  line-height: 1.5;
}
</style>
