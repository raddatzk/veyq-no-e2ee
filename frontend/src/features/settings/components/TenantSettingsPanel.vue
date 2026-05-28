<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import FileUpload, { type FileUploadSelectEvent } from 'primevue/fileupload'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import ProgressSpinner from 'primevue/progressspinner'
import Select from 'primevue/select'
import ManagementList from '../../../shared/components/ManagementList.vue'
import LocaleMarkdownEditor from '../../../shared/components/LocaleMarkdownEditor.vue'
import { SUPPORTED_LOCALES, type SupportedLocale } from '../../../i18n'
import { messages } from '../../../i18n/messages'
import { getErrorMessage, isApiStatus } from '../../../shared/utils/errors'
import { deleteTenant as deleteTenantApi, tenants as loadTenantSummaries } from '../../../api/tenantClient'
import { useAuthState } from '../../../shared/state/useAuthState'
import {
  deleteTenantLogo,
  getTenantSettings,
  getTenantUsers,
  inviteTenantUser,
  reinviteTenantUser,
  updateTenantLogo,
  updateTenantSettings,
  updateTenantUserRole,
  type TenantSettingsResponse,
  type TenantRole,
  type TenantUserSummary
} from '../api'

const props = defineProps<{
  tenantName: string
}>()

type TermsTemplateClauseId = keyof typeof messages.de.share.termsTemplateMarkdown

const router = useRouter()
const { t } = useI18n()
const { authState, setTenants } = useAuthState()
const users = ref<TenantUserSummary[]>([])
const loading = ref(false)
const saving = ref(false)
const savingLogo = ref(false)
const error = ref('')
const message = ref('')
const inviteDialogVisible = ref(false)
const deleteDialogVisible = ref(false)
const deleteConfirmation = ref('')
const inviteEmail = ref('')
const inviteTenantRole = ref<TenantRole>('TenantMember')
const tenantSettings = ref<TenantSettingsResponse | null>(null)
const termsMarkdownByLocale = ref<Record<string, string>>({})
const termsTemplateDialogVisible = ref(false)
const termsTemplateLocale = ref('de')
const selectedTemplateClauseIds = ref<string[]>([])
const templateContactMode = ref<'owners' | 'custom'>('owners')
const templateContactText = ref('')
const templateImportMode = ref<'append' | 'replace'>('append')

const tenantDisplayName = computed(
  () =>
    tenantSettings.value?.displayName ||
    authState.tenants.find((tenant) => tenant.name === props.tenantName)?.displayName ||
    props.tenantName
)
const canDeleteTenant = computed(() =>
  users.value.some(
    (user) =>
      user.user.userId === authState.user?.userId &&
      user.status === 'Active' &&
      user.role === 'TenantOwner'
  )
)
const canConfirmDeleteTenant = computed(
  () => deleteConfirmation.value.trim() === tenantDisplayName.value.trim() && !saving.value
)

const roleOptions = computed(() => [
  { label: t('tenant.roles.TenantOwner'), value: 'TenantOwner' },
  { label: t('tenant.roles.TenantMember'), value: 'TenantMember' }
])
const localeOptions = computed(() =>
  SUPPORTED_LOCALES.map((locale) => ({
    label: t(`navigation.locales.${locale}`),
    value: locale
  }))
)
const tenantOwnerEmails = computed(() =>
  users.value
    .filter((user) => user.role === 'TenantOwner' && user.status === 'Active' && user.email)
    .map((user) => user.email!)
)
const canSaveTerms = computed(() => Boolean(tenantSettings.value) && !saving.value)
const templateClauses = computed(() => [
  { id: 'purpose', label: t('share.termsTemplatePurpose'), markdown: termsTemplateMarkdown('purpose') },
  { id: 'privacy', label: t('share.termsTemplatePrivacy'), markdown: termsTemplateMarkdown('privacy') },
  { id: 'rights', label: t('share.termsTemplateRights'), markdown: termsTemplateMarkdown('rights') },
  { id: 'children', label: t('share.termsTemplateChildren'), markdown: termsTemplateMarkdown('children') },
  { id: 'illegal', label: t('share.termsTemplateIllegal'), markdown: termsTemplateMarkdown('illegal') },
  { id: 'sharing', label: t('share.termsTemplateSharing'), markdown: termsTemplateMarkdown('sharing') },
  { id: 'uploads', label: t('share.termsTemplateUploads'), markdown: termsTemplateMarkdown('uploads') },
  { id: 'availability', label: t('share.termsTemplateAvailability'), markdown: termsTemplateMarkdown('availability') },
  { id: 'moderation', label: t('share.termsTemplateModeration'), markdown: termsTemplateMarkdown('moderation') },
  { id: 'external', label: t('share.termsTemplateExternal'), markdown: termsTemplateMarkdown('external') },
  { id: 'contact', label: t('share.termsTemplateContact'), markdown: termsTemplateMarkdown('contact') }
])
const canImportTemplate = computed(() =>
  Boolean(termsTemplateLocale.value.trim()) &&
    selectedTemplateClauseIds.value.length > 0 &&
    (
      (templateContactMode.value === 'owners' && tenantOwnerEmails.value.length > 0) ||
      (templateContactMode.value === 'custom' && Boolean(templateContactText.value.trim()))
    )
)

onMounted(load)
watch(() => props.tenantName, load)

async function load() {
  loading.value = true
  error.value = ''
  message.value = ''
  try {
    const [nextUsers, nextSettings] = await Promise.all([
      getTenantUsers(props.tenantName),
      getTenantSettings(props.tenantName)
    ])
    users.value = nextUsers
    applyTenantSettings(nextSettings)
    termsMarkdownByLocale.value = { ...(nextSettings.termsMarkdownByLocale || {}) }
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.membersLoadFailed'))
  } finally {
    loading.value = false
  }
}

async function uploadLogo(event: FileUploadSelectEvent) {
  const file = event.files?.[0]
  if (!file) return
  savingLogo.value = true
  error.value = ''
  message.value = ''
  try {
    applyTenantSettings(await updateTenantLogo(props.tenantName, file))
    message.value = t('tenant.logoSaveSuccess')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.logoSaveFailed'))
  } finally {
    savingLogo.value = false
  }
}

async function removeLogo() {
  savingLogo.value = true
  error.value = ''
  message.value = ''
  try {
    applyTenantSettings(await deleteTenantLogo(props.tenantName))
    message.value = t('tenant.logoDeleteSuccess')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.logoDeleteFailed'))
  } finally {
    savingLogo.value = false
  }
}

function openTemplateDialog() {
  termsTemplateLocale.value =
    Object.keys(termsMarkdownByLocale.value).find((locale) =>
      SUPPORTED_LOCALES.includes(locale as (typeof SUPPORTED_LOCALES)[number])
    ) || SUPPORTED_LOCALES[0]
  selectedTemplateClauseIds.value = templateClauses.value.map((clause) => clause.id)
  templateContactMode.value = 'owners'
  templateContactText.value = ''
  templateImportMode.value = 'append'
  termsTemplateDialogVisible.value = true
}

function toggleTemplateClause(clauseId: string, checked: boolean) {
  selectedTemplateClauseIds.value = checked
    ? Array.from(new Set([...selectedTemplateClauseIds.value, clauseId]))
    : selectedTemplateClauseIds.value.filter((id) => id !== clauseId)
}

function importTermsTemplate() {
  if (!canImportTemplate.value) return
  const locale = termsTemplateLocale.value.trim().toLowerCase()
  const selected = templateClauses.value
    .filter((clause) => selectedTemplateClauseIds.value.includes(clause.id))
    .map((clause) => clause.markdown)
    .join('\n\n')
  const current = termsMarkdownByLocale.value[locale]?.trim()
  termsMarkdownByLocale.value = {
    ...termsMarkdownByLocale.value,
    [locale]: templateImportMode.value === 'append' && current ? `${current}\n\n${selected}` : selected
  }
  termsTemplateDialogVisible.value = false
}

async function saveTerms() {
  if (!tenantSettings.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    tenantSettings.value = await updateTenantSettings(props.tenantName, {
      displayName: tenantSettings.value.displayName,
      termsMarkdownByLocale: termsMarkdownByLocale.value
    })
    applyTenantSettings(tenantSettings.value)
    termsMarkdownByLocale.value = { ...(tenantSettings.value.termsMarkdownByLocale || {}) }
    message.value = t('share.termsSaved')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('share.termsSaveFailed'))
  } finally {
    saving.value = false
  }
}

function termsTemplateMarkdown(clauseId: TermsTemplateClauseId): string {
  return messages[templateLocale()].share.termsTemplateMarkdown[clauseId].replace('{contact}', templateContact())
}

function templateLocale(): SupportedLocale {
  return SUPPORTED_LOCALES.includes(termsTemplateLocale.value as SupportedLocale)
    ? termsTemplateLocale.value as SupportedLocale
    : SUPPORTED_LOCALES[0]
}

function templateContact(): string {
  const contact =
    templateContactMode.value === 'owners'
      ? tenantOwnerEmails.value.join(', ')
      : templateContactText.value.trim()
  return contact
}

function openInviteDialog() {
  inviteEmail.value = ''
  inviteTenantRole.value = 'TenantMember'
  error.value = ''
  message.value = ''
  inviteDialogVisible.value = true
}

function openDeleteDialog() {
  deleteConfirmation.value = ''
  error.value = ''
  message.value = ''
  deleteDialogVisible.value = true
}

async function inviteUserByEmail() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    upsertUser(await inviteTenantUser(props.tenantName, inviteEmail.value, inviteTenantRole.value))
    inviteDialogVisible.value = false
    message.value = t('tenant.inviteSent')
  } catch (caught) {
    if (isApiStatus(caught, 409)) {
      error.value = t('tenant.alreadyMember')
    } else {
      error.value = getErrorMessage(caught, t('tenant.inviteFailed'))
    }
  } finally {
    saving.value = false
  }
}

async function reinviteUser(user: TenantUserSummary) {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    upsertUser(await reinviteTenantUser(props.tenantName, user.user.userId))
    message.value = t('tenant.inviteResent')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.inviteResendFailed'))
  } finally {
    saving.value = false
  }
}

async function changeRole(user: TenantUserSummary, role: TenantRole) {
  error.value = ''
  message.value = ''
  try {
    upsertUser(await updateTenantUserRole(props.tenantName, user.user.userId, role))
    message.value = t('tenant.roleSaved')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.roleSaveFailed'))
  }
}

async function submitDeleteTenant() {
  if (!canConfirmDeleteTenant.value) return
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    await deleteTenantApi(props.tenantName)
    setTenants(await loadTenantSummaries())
    deleteDialogVisible.value = false
    await router.push('/')
  } catch (caught) {
    error.value = getErrorMessage(caught, t('tenant.deleteFailed'))
  } finally {
    saving.value = false
  }
}

function upsertUser(user: TenantUserSummary) {
  const index = users.value.findIndex((item) => item.user.userId === user.user.userId)
  if (index === -1) {
    users.value = [...users.value, user].sort((left, right) =>
      userSortLabel(left).localeCompare(userSortLabel(right))
    )
  } else {
    users.value.splice(index, 1, user)
  }
}

function userSortLabel(user: TenantUserSummary): string {
  return user.email || user.user.displayName
}

function applyTenantSettings(settings: TenantSettingsResponse) {
  tenantSettings.value = settings
  setTenants(
    authState.tenants.map((tenant) =>
      tenant.name === settings.name
        ? { ...tenant, displayName: settings.displayName, logoUrl: settings.logoUrl }
        : tenant
    )
  )
}

function tenantInitial(value: string): string {
  return value.trim().charAt(0).toUpperCase() || 'V'
}
</script>

<template>
  <section class="veyq-surface settings-panel">
    <h1>{{ tenantDisplayName }}</h1>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>
    <Message v-if="message" severity="success" :closable="false">{{ message }}</Message>

    <div v-if="loading" class="veyq-loading">
      <ProgressSpinner class="small-spinner" />
      <span>{{ t('tenant.membersLoading') }}</span>
    </div>

    <template v-else>
      <section v-if="tenantSettings" class="settings-form">
        <h2>{{ t('tenant.logoTitle') }}</h2>
        <p>{{ t('tenant.logoCopy') }}</p>
        <div class="tenant-logo-settings">
          <img v-if="tenantSettings.logoUrl" class="tenant-logo-image" :src="tenantSettings.logoUrl" alt="" />
          <span v-else class="tenant-logo-fallback">{{ tenantInitial(tenantSettings.displayName) }}</span>
          <div class="veyq-button-row">
            <FileUpload
              mode="basic"
              accept="image/png,image/jpeg"
              :choose-label="t('tenant.logoChoose')"
              custom-upload
              :disabled="savingLogo"
              @select="uploadLogo"
            />
            <Button
              type="button"
              icon="pi pi-trash"
              severity="secondary"
              text
              :disabled="!tenantSettings.logoUrl"
              :loading="savingLogo"
              :aria-label="t('tenant.logoRemove')"
              @click="removeLogo"
            />
          </div>
        </div>
      </section>

      <section class="settings-form">
        <h2>{{ t('share.termsTitle') }}</h2>
        <p>{{ t('share.termsCopy') }}</p>
        <LocaleMarkdownEditor v-model="termsMarkdownByLocale" :label="t('share.termsLanguages')" compact />
        <div class="veyq-button-row">
          <Button
            type="button"
            :label="t('share.termsTemplateInsert')"
            icon="pi pi-file-import"
            severity="secondary"
            outlined
            @click="openTemplateDialog"
          />
          <Button
            type="button"
            :label="t('common.save')"
            icon="pi pi-save"
            :loading="saving"
            :disabled="!canSaveTerms"
            @click="saveTerms"
          />
        </div>
      </section>

      <section class="settings-form">
        <div class="settings-section-header">
          <div>
            <h2>{{ t('tenant.members') }}</h2>
          </div>
          <Button :label="t('tenant.invite')" icon="pi pi-user-plus" @click="openInviteDialog" />
        </div>
        <ManagementList>
          <div v-for="user in users" :key="user.user.userId" class="veyq-list-row">
            <div class="veyq-row-main">
              <strong class="veyq-row-title">{{ user.user.displayName }}</strong>
              <span v-if="user.email" class="veyq-row-subtitle">{{ user.email }}</span>
              <div class="veyq-row-meta">
                <span
                  class="veyq-badge"
                  :class="user.status === 'Active' ? 'success' : 'warning'"
                >
                  {{ user.status === 'Active' ? t('common.active') : t('common.pending') }}
                </span>
              </div>
            </div>
            <Select
              class="management-control"
              :model-value="user.role"
              :options="roleOptions"
              option-label="label"
              option-value="value"
              :aria-label="t('tenant.role')"
              @update:model-value="changeRole(user, $event)"
            />
            <div class="management-actions">
              <Button
                v-if="user.status === 'Pending'"
                icon="pi pi-send"
                severity="secondary"
                outlined
                size="small"
                :aria-label="t('tenant.inviteResend')"
                :title="t('tenant.inviteResend')"
                :loading="saving"
                @click="reinviteUser(user)"
              />
            </div>
          </div>
        </ManagementList>
      </section>

      <section v-if="canDeleteTenant" class="settings-form settings-danger-zone">
        <h2>{{ t('tenant.dangerZone') }}</h2>
        <Button
          :label="t('common.delete')"
          icon="pi pi-trash"
          severity="danger"
          outlined
          @click="openDeleteDialog"
        />
      </section>
    </template>

    <Dialog v-model:visible="inviteDialogVisible" :header="t('tenant.invite')" modal class="tenant-dialog">
      <form class="veyq-form" @submit.prevent="inviteUserByEmail">
        <label>
          {{ t('common.email') }}
          <InputText v-model="inviteEmail" type="email" autocomplete="email" required />
        </label>
        <label>
          {{ t('common.role') }}
          <Select
            v-model="inviteTenantRole"
            :options="roleOptions"
            option-label="label"
            option-value="value"
          />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            type="button"
            :label="t('common.cancel')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="inviteDialogVisible = false"
          />
          <Button type="submit" :label="t('tenant.invite')" icon="pi pi-send" :loading="saving" />
        </div>
      </form>
    </Dialog>

    <Dialog v-model:visible="deleteDialogVisible" :header="t('common.delete')" modal class="tenant-dialog">
      <div class="veyq-form">
        <p>
          {{ t('tenant.deleteConfirm', { name: tenantDisplayName }) }}
        </p>
        <label>
          {{ t('settings.confirmationDisplayName') }}
          <InputText v-model="deleteConfirmation" autocomplete="off" :placeholder="tenantDisplayName" />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            type="button"
            :label="t('common.cancel')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="deleteDialogVisible = false"
          />
          <Button
            :label="t('common.delete')"
            icon="pi pi-trash"
            severity="danger"
            :disabled="!canConfirmDeleteTenant"
            :loading="saving"
            @click="submitDeleteTenant"
          />
        </div>
      </div>
    </Dialog>

    <Dialog v-model:visible="termsTemplateDialogVisible" :header="t('share.termsTemplateTitle')" modal class="tenant-dialog">
      <form class="veyq-form" @submit.prevent="importTermsTemplate">
        <label>
          {{ t('share.noticeLocale') }}
          <Select
            v-model="termsTemplateLocale"
            :options="localeOptions"
            option-label="label"
            option-value="value"
          />
        </label>
        <fieldset class="terms-template-fieldset">
          <legend>{{ t('share.termsTemplateClauses') }}</legend>
          <label v-for="clause in templateClauses" :key="clause.id" class="checkbox-row">
            <input
              type="checkbox"
              :checked="selectedTemplateClauseIds.includes(clause.id)"
              @change="toggleTemplateClause(clause.id, ($event.target as HTMLInputElement).checked)"
            />
            <span>{{ clause.label }}</span>
          </label>
        </fieldset>
        <fieldset class="terms-template-fieldset">
          <legend>{{ t('share.termsTemplateContactChoice') }}</legend>
          <label class="checkbox-row">
            <input v-model="templateContactMode" type="radio" value="owners" />
            <span>{{ t('share.termsTemplateUseTenantOwners') }}</span>
          </label>
          <label class="checkbox-row">
            <input v-model="templateContactMode" type="radio" value="custom" />
            <span>{{ t('share.termsTemplateUseCustomContact') }}</span>
          </label>
          <InputText
            v-if="templateContactMode === 'custom'"
            v-model="templateContactText"
            autocomplete="off"
            :placeholder="t('share.termsTemplateContactPlaceholder')"
          />
        </fieldset>
        <fieldset class="terms-template-fieldset">
          <legend>{{ t('share.termsTemplateImportMode') }}</legend>
          <label class="checkbox-row">
            <input v-model="templateImportMode" type="radio" value="append" />
            <span>{{ t('share.termsTemplateAppend') }}</span>
          </label>
          <label class="checkbox-row">
            <input v-model="templateImportMode" type="radio" value="replace" />
            <span>{{ t('share.termsTemplateReplace') }}</span>
          </label>
        </fieldset>
        <div class="veyq-dialog-action-row">
          <Button
            type="button"
            :label="t('common.cancel')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="termsTemplateDialogVisible = false"
          />
          <Button
            type="submit"
            :label="t('share.termsTemplateInsert')"
            icon="pi pi-file-import"
            :disabled="!canImportTemplate"
          />
        </div>
      </form>
    </Dialog>
  </section>
</template>
