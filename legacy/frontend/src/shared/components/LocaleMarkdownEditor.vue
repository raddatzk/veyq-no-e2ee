<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Button from 'primevue/button'
import Dialog from 'primevue/dialog'
import Select from 'primevue/select'
import { SUPPORTED_LOCALES } from '../../i18n'

const model = defineModel<Record<string, string>>({ required: true })
const props = defineProps<{
  label: string
  addLabel?: string
  compact?: boolean
}>()

const { t, te } = useI18n()
const nextLocale = ref('')
const editingLocale = ref('')
const editingDraft = ref('')
const editingOpen = ref(false)
const localeOptions = computed(() =>
  SUPPORTED_LOCALES.map((locale) => ({
    label: t(`navigation.locales.${locale}`),
    value: locale,
    disabled: model.value[locale] !== undefined
  }))
)

const entries = computed(() =>
  Object.entries(model.value || {})
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([locale, markdown]) => ({ locale, label: localeLabel(locale), markdown }))
)

function addLocale() {
  const locale = normalizeLocale(nextLocale.value)
  if (!locale || model.value[locale] !== undefined) return
  model.value = { ...model.value, [locale]: '' }
  nextLocale.value = ''
  if (props.compact) openEditor(locale)
}

function updateLocale(locale: string, markdown: string) {
  model.value = { ...model.value, [locale]: markdown }
}

function removeLocale(locale: string) {
  const next = { ...model.value }
  delete next[locale]
  model.value = next
}

function openEditor(locale: string) {
  editingLocale.value = locale
  editingDraft.value = model.value[locale] || ''
  editingOpen.value = true
}

function saveEditor() {
  if (!editingLocale.value) return
  updateLocale(editingLocale.value, editingDraft.value)
  editingOpen.value = false
}

function normalizeLocale(locale: string): string {
  return locale.trim().toLowerCase()
}

function localeLabel(locale: string): string {
  if (!locale) return ''
  const key = `navigation.locales.${locale}`
  return te(key) ? t(key) : locale
}
</script>

<template>
  <section class="locale-markdown-editor">
    <div class="locale-markdown-editor-header">
      <strong>{{ props.label }}</strong>
      <div class="locale-markdown-editor-add">
        <Select
          v-model="nextLocale"
          :options="localeOptions"
          option-label="label"
          option-value="value"
          option-disabled="disabled"
          :placeholder="t('share.noticeLocalePlaceholder')"
        />
        <Button
          type="button"
          icon="pi pi-plus"
          :label="props.addLabel || t('share.noticeLocaleAdd')"
          :disabled="!normalizeLocale(nextLocale) || model[normalizeLocale(nextLocale)] !== undefined"
          @click="addLocale"
        />
      </div>
    </div>

    <div v-if="entries.length && compact" class="locale-markdown-editor-list">
      <div v-for="entry in entries" :key="entry.locale" class="locale-markdown-editor-row">
        <strong>{{ entry.label }}</strong>
        <div class="locale-markdown-editor-row-actions">
          <Button
            type="button"
            icon="pi pi-pencil"
            :label="t('share.noticeLocaleEdit')"
            severity="secondary"
            outlined
            size="small"
            @click="openEditor(entry.locale)"
          />
          <Button
            type="button"
            icon="pi pi-trash"
            severity="secondary"
            text
            size="small"
            :aria-label="t('share.noticeLocaleRemove', { locale: entry.label })"
            @click="removeLocale(entry.locale)"
          />
        </div>
      </div>
    </div>

    <div v-else-if="entries.length" class="locale-markdown-editor-list">
      <label v-for="entry in entries" :key="entry.locale" class="locale-markdown-editor-entry">
        <span>
          <strong>{{ entry.label }}</strong>
          <Button
            type="button"
            icon="pi pi-trash"
            severity="secondary"
            text
            size="small"
            :aria-label="t('share.noticeLocaleRemove', { locale: entry.label })"
            @click="removeLocale(entry.locale)"
          />
        </span>
        <textarea
          :value="entry.markdown"
          rows="7"
          :placeholder="t('share.noticeMarkdownPlaceholder')"
          @input="updateLocale(entry.locale, ($event.target as HTMLTextAreaElement).value)"
        />
      </label>
    </div>
    <p v-else class="locale-markdown-editor-empty">{{ t('share.noticeEmpty') }}</p>

    <Dialog
      v-if="compact"
      v-model:visible="editingOpen"
      modal
      class="tenant-dialog locale-markdown-dialog"
      :header="t('share.noticeLocaleDialogTitle', { locale: localeLabel(editingLocale) })"
    >
      <div class="veyq-form">
        <label>
          {{ t('share.noticeMarkdown') }}
          <textarea
            v-model="editingDraft"
            rows="12"
            :placeholder="t('share.noticeMarkdownPlaceholder')"
          />
        </label>
        <div class="veyq-dialog-action-row">
          <Button
            type="button"
            :label="t('common.cancel')"
            icon="pi pi-times"
            severity="secondary"
            outlined
            @click="editingOpen = false"
          />
          <Button type="button" :label="t('common.save')" icon="pi pi-save" @click="saveEditor" />
        </div>
      </div>
    </Dialog>
  </section>
</template>
