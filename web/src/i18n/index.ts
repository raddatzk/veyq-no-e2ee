import { createI18n } from 'vue-i18n'

// Focused message catalog for the setup slice (EN + DE). The full legacy catalog arrives as later UI
// increments land; keys here mirror the legacy keys 1:1 so the reused views need no string changes.
export type SupportedLocale = 'en' | 'de'

const en = {
  common: { email: 'Email', password: 'Password', cancel: 'Cancel', create: 'Create', show: 'Show', hide: 'Hide', unreachable: 'This instance can’t be reached right now. Please try again.' },
  tenant: {
    manageTitle: 'Manage tenants',
    manageSub: 'Create tenants and manage their owner invites. You are not a member of any tenant and cannot read its content.',
    create: 'Create tenant',
    name: 'Tenant name',
    domain: 'Domain',
    domainHint: 'The web address this tenant is reached at, e.g. family.example.com.',
    ownerEmail: 'Owner email',
    domainTaken: 'That domain is already in use by another tenant.',
    createFailed: 'Could not create the tenant. Please try again.',
    inviteSent: 'Tenant created — an owner invite was sent.',
    statusPending: 'Pending',
    statusActive: 'Active',
    resend: 'Resend invite',
    reassign: 'Change email',
    revoke: 'Revoke',
    reassignTitle: 'Reassign the owner invite',
    reassignSubmit: 'Send invite',
    resent: 'Invite resent.',
    reassigned: 'Invite reassigned.',
    revoked: 'Pending tenant revoked.',
    revokeConfirm: 'Revoke this pending tenant? Its invite stops working.',
    actionFailed: 'That action failed. Please try again.',
    loading: 'Loading tenants…',
    loadFailed: 'Could not load tenants. Please try again.',
    notAuthorized: 'Only the server owner can manage tenants.',
    empty: 'No tenants yet. Create the first one.',
    join: {
      title: 'Accept your invitation',
      loading: 'Checking your invitation…',
      invalid: 'This invitation link is invalid.',
      expired: 'This invitation is no longer valid. Ask the operator to resend it.',
      invitedTo: 'You have been invited to own “{tenant}” ({domain}).',
      registerSub: 'Create your account for {email} to take ownership.',
      displayName: 'Your name',
      continue: 'Continue',
      saveKitTitle: 'Save your Secret Key',
      saveKitSub: 'This Secret Key recovers your account if you forget your password. Save it somewhere safe — it is shown only once.',
      kitConfirm: 'I have saved my Secret Key somewhere safe.',
      join: 'Accept & activate',
      signinSub: 'Sign in as {email} to take ownership.',
      done: '“{tenant}” is now active and yours.',
      doneNote: 'Sign in to start using your space.',
      failed: 'Could not accept the invitation. Please try again.',
      emailTaken: 'That email already has an account — sign in instead.',
      wrongOwner: 'You are signed in as a different user than the one invited.',
    },
  },
  setup: {
    title: 'Set up this instance',
    sub: 'Create the first owner account. All keys are generated on this device — the server only ever sees ciphertext.',
    displayName: 'Your name',
    submit: 'Confirm',
    submitting: 'Confirming…',
    failed: 'Setup failed. Please try again.',
    kitTitle: 'Save your Emergency Kit',
    kitSub: 'Your Secret Key recovers your account if you ever forget your password. Save the kit somewhere safe before continuing.',
    kitProceed: 'Continue to sign in',
  },
  kit: {
    download: 'Download Emergency Kit (PDF)',
    downloaded: 'Emergency Kit downloaded.',
    downloadFailed: 'Could not create the Emergency Kit. Please try again.',
    backupDownloadConfirm: 'I have saved my Emergency Kit somewhere safe.',
  },
  password: {
    minLength: 'Use at least 10 characters.',
    strong: 'Nice and long — that is a strong password.',
    pwnedWarning: 'This password appeared in {count} known data breaches. Consider a different one.',
  },
  login: {
    title: 'Sign in',
    submit: 'Sign in',
    submitting: 'Signing in…',
    failed: 'Sign-in failed. Check your email and password.',
    lockedTitle: 'Signed in',
    lockedNote: 'Your session is active. Unlocking your encrypted memories with your Secret Key arrives in a later change.',
    signOut: 'Sign out',
  },
  unlock: {
    title: 'Unlock your memories',
    note: 'Signed in. Enter your Secret Key from your Emergency Kit to open your encrypted memories on this device — it never leaves your device.',
    secretKey: 'Secret Key',
    upload: 'Upload Emergency Kit',
    scan: 'Scan QR code',
    scanHint: 'Point your camera at the QR code in your Emergency Kit.',
    scanError: 'Camera unavailable — upload your kit or paste your Secret Key.',
    kitUnreadable: 'Couldn’t read that kit. Try another file or paste your Secret Key.',
    submit: 'Unlock',
    submitting: 'Unlocking…',
    failed: 'Unlock failed. Check your password and Secret Key.',
    unlockedTitle: 'Unlocked',
    unlockedNote: 'Your memories are unlocked on this device for this session.',
    trustDevice: 'Trust this device',
    trustDeviceHint: 'Next time, unlock on this device with just your password — your Secret Key stays in your kit.',
    trustFailed: 'Couldn’t trust this device — you unlocked fine, but you’ll need your Secret Key next time.',
    untrust: 'Untrust this device',
  },
  shell: {
    appName: 'veyq',
    spaces: 'Spaces',
    personal: 'Personal',
    favorites: 'Favorites',
    logout: 'Sign out',
    search: 'Search…',
    language: 'Language',
    theme: 'Appearance',
    themeSystem: 'System',
    themeLight: 'Light',
    themeDark: 'Dark',
  },
  home: {
    loading: 'Opening your space…',
    empty: 'Your space is empty',
    emptyHint: 'Create a folder or a Memory to begin.',
    noTenant: 'No space found',
    noTenantHint: 'This account isn’t part of a space on this instance yet.',
    error: 'Could not open your space. Please try again.',
  },
  memory: {
    memories: 'Memories',
    folders: 'Folders',
    memoryEmpty: 'No Memories here yet.',
    folderEmpty: 'No folders here yet.',
    detailComing: 'Photos and journal for this Memory arrive in a later update.',
    create: 'New Memory',
    folderCreate: 'New folder',
    createMemoryTitle: 'Create a Memory',
    createFolderTitle: 'Create a folder',
    renameTitle: 'Rename',
    nameLabel: 'Name',
    save: 'Save',
    rename: 'Rename',
    delete: 'Delete',
    favorite: 'Favorite',
    unfavorite: 'Remove favorite',
    slugTaken: 'A folder or Memory with a similar name already exists here.',
    illegalStructure: 'That isn’t allowed here.',
    saveFailed: 'That didn’t work. Please try again.',
    deleteFolderTitle: 'Delete folder',
    deleteMemoryTitle: 'Delete Memory',
    deleteFolderCopy: 'Delete “{name}” and everything inside it? This can’t be undone.',
    deleteMemoryCopy: 'Delete “{name}”? This can’t be undone.',
    deleteFailed: 'Could not delete it. Please try again.',
  },
}

const de: typeof en = {
  common: { email: 'E-Mail', password: 'Passwort', cancel: 'Abbrechen', create: 'Erstellen', show: 'Anzeigen', hide: 'Verbergen', unreachable: 'Diese Instanz ist gerade nicht erreichbar. Bitte erneut versuchen.' },
  tenant: {
    manageTitle: 'Tenants verwalten',
    manageSub: 'Lege Tenants an und verwalte ihre Owner-Einladungen. Du bist in keinem Tenant Mitglied und kannst dessen Inhalte nicht lesen.',
    create: 'Tenant anlegen',
    name: 'Tenant-Name',
    domain: 'Domain',
    domainHint: 'Die Web-Adresse, unter der dieser Tenant erreichbar ist, z. B. family.example.com.',
    ownerEmail: 'Owner-E-Mail',
    domainTaken: 'Diese Domain wird bereits von einem anderen Tenant verwendet.',
    createFailed: 'Tenant konnte nicht angelegt werden. Bitte erneut versuchen.',
    inviteSent: 'Tenant angelegt — eine Owner-Einladung wurde gesendet.',
    statusPending: 'Ausstehend',
    statusActive: 'Aktiv',
    resend: 'Einladung erneut senden',
    reassign: 'E-Mail ändern',
    revoke: 'Widerrufen',
    reassignTitle: 'Owner-Einladung neu zuweisen',
    reassignSubmit: 'Einladung senden',
    resent: 'Einladung erneut gesendet.',
    reassigned: 'Einladung neu zugewiesen.',
    revoked: 'Ausstehender Tenant widerrufen.',
    revokeConfirm: 'Diesen ausstehenden Tenant widerrufen? Seine Einladung wird ungültig.',
    actionFailed: 'Die Aktion ist fehlgeschlagen. Bitte erneut versuchen.',
    loading: 'Tenants werden geladen…',
    loadFailed: 'Tenants konnten nicht geladen werden. Bitte erneut versuchen.',
    notAuthorized: 'Nur der Server-Owner kann Tenants verwalten.',
    empty: 'Noch keine Tenants. Lege den ersten an.',
    join: {
      title: 'Einladung annehmen',
      loading: 'Einladung wird geprüft…',
      invalid: 'Dieser Einladungslink ist ungültig.',
      expired: 'Diese Einladung ist nicht mehr gültig. Bitte den Betreiber, sie erneut zu senden.',
      invitedTo: 'Du wurdest eingeladen, „{tenant}“ ({domain}) zu besitzen.',
      registerSub: 'Lege dein Konto für {email} an, um die Inhaberschaft zu übernehmen.',
      displayName: 'Dein Name',
      continue: 'Weiter',
      saveKitTitle: 'Secret Key sichern',
      saveKitSub: 'Dieser Secret Key stellt dein Konto wieder her, falls du dein Passwort vergisst. Sichere ihn — er wird nur einmal angezeigt.',
      kitConfirm: 'Ich habe meinen Secret Key sicher gespeichert.',
      join: 'Annehmen & aktivieren',
      signinSub: 'Melde dich als {email} an, um die Inhaberschaft zu übernehmen.',
      done: '„{tenant}“ ist jetzt aktiv und gehört dir.',
      doneNote: 'Melde dich an, um deinen Bereich zu nutzen.',
      failed: 'Einladung konnte nicht angenommen werden. Bitte erneut versuchen.',
      emailTaken: 'Diese E-Mail hat bereits ein Konto — bitte stattdessen anmelden.',
      wrongOwner: 'Du bist als ein anderer Nutzer angemeldet als der eingeladene.',
    },
  },
  setup: {
    title: 'Diese Instanz einrichten',
    sub: 'Lege das erste Besitzer-Konto an. Alle Schlüssel entstehen auf diesem Gerät — der Server sieht nur Chiffretext.',
    displayName: 'Dein Name',
    submit: 'Bestätigen',
    submitting: 'Wird bestätigt…',
    failed: 'Einrichtung fehlgeschlagen. Bitte erneut versuchen.',
    kitTitle: 'Notfall-Kit sichern',
    kitSub: 'Dein Secret Key stellt dein Konto wieder her, falls du dein Passwort vergisst. Sichere das Kit, bevor du fortfährst.',
    kitProceed: 'Weiter zur Anmeldung',
  },
  kit: {
    download: 'Notfall-Kit herunterladen (PDF)',
    downloaded: 'Notfall-Kit heruntergeladen.',
    downloadFailed: 'Notfall-Kit konnte nicht erstellt werden. Bitte erneut versuchen.',
    backupDownloadConfirm: 'Ich habe mein Notfall-Kit sicher gespeichert.',
  },
  password: {
    minLength: 'Mindestens 10 Zeichen verwenden.',
    strong: 'Schön lang — ein starkes Passwort.',
    pwnedWarning: 'Dieses Passwort tauchte in {count} bekannten Datenlecks auf. Wähle besser ein anderes.',
  },
  login: {
    title: 'Anmelden',
    submit: 'Anmelden',
    submitting: 'Wird angemeldet…',
    failed: 'Anmeldung fehlgeschlagen. Prüfe E-Mail und Passwort.',
    lockedTitle: 'Angemeldet',
    lockedNote: 'Deine Sitzung ist aktiv. Das Entsperren deiner verschlüsselten Erinnerungen mit deinem Secret Key kommt in einer späteren Änderung.',
    signOut: 'Abmelden',
  },
  unlock: {
    title: 'Erinnerungen entsperren',
    note: 'Angemeldet. Gib deinen Secret Key aus dem Notfall-Kit ein, um deine verschlüsselten Erinnerungen auf diesem Gerät zu öffnen — er verlässt dein Gerät nie.',
    secretKey: 'Secret Key',
    upload: 'Notfall-Kit hochladen',
    scan: 'QR-Code scannen',
    scanHint: 'Richte deine Kamera auf den QR-Code in deinem Notfall-Kit.',
    scanError: 'Kamera nicht verfügbar — lade dein Kit hoch oder füge deinen Secret Key ein.',
    kitUnreadable: 'Konnte das Kit nicht lesen. Versuche eine andere Datei oder füge deinen Secret Key ein.',
    submit: 'Entsperren',
    submitting: 'Wird entsperrt…',
    failed: 'Entsperren fehlgeschlagen. Prüfe Passwort und Secret Key.',
    unlockedTitle: 'Entsperrt',
    unlockedNote: 'Deine Erinnerungen sind auf diesem Gerät für diese Sitzung entsperrt.',
    trustDevice: 'Diesem Gerät vertrauen',
    trustDeviceHint: 'Beim nächsten Mal entsperrst du auf diesem Gerät nur mit deinem Passwort — dein Secret Key bleibt im Kit.',
    trustFailed: 'Konnte diesem Gerät nicht vertrauen — entsperrt hat es geklappt, aber beim nächsten Mal brauchst du deinen Secret Key.',
    untrust: 'Vertrauen aufheben',
  },
  shell: {
    appName: 'veyq',
    spaces: 'Bereiche',
    personal: 'Persönlich',
    favorites: 'Favoriten',
    logout: 'Abmelden',
    search: 'Suchen…',
    language: 'Sprache',
    theme: 'Darstellung',
    themeSystem: 'System',
    themeLight: 'Hell',
    themeDark: 'Dunkel',
  },
  home: {
    loading: 'Dein Bereich wird geöffnet…',
    empty: 'Dein Bereich ist leer',
    emptyHint: 'Lege einen Ordner oder eine Erinnerung an, um zu beginnen.',
    noTenant: 'Kein Bereich gefunden',
    noTenantHint: 'Dieses Konto gehört auf dieser Instanz noch zu keinem Bereich.',
    error: 'Dein Bereich konnte nicht geöffnet werden. Bitte versuche es erneut.',
  },
  memory: {
    memories: 'Erinnerungen',
    folders: 'Ordner',
    memoryEmpty: 'Hier gibt es noch keine Erinnerungen.',
    folderEmpty: 'Hier gibt es noch keine Ordner.',
    detailComing: 'Fotos und Journal für diese Erinnerung kommen in einem späteren Update.',
    create: 'Neue Erinnerung',
    folderCreate: 'Neuer Ordner',
    createMemoryTitle: 'Erinnerung anlegen',
    createFolderTitle: 'Ordner anlegen',
    renameTitle: 'Umbenennen',
    nameLabel: 'Name',
    save: 'Speichern',
    rename: 'Umbenennen',
    delete: 'Löschen',
    favorite: 'Favorit',
    unfavorite: 'Favorit entfernen',
    slugTaken: 'Ein Ordner oder eine Erinnerung mit ähnlichem Namen existiert hier bereits.',
    illegalStructure: 'Das ist hier nicht erlaubt.',
    saveFailed: 'Das hat nicht geklappt. Bitte versuche es erneut.',
    deleteFolderTitle: 'Ordner löschen',
    deleteMemoryTitle: 'Erinnerung löschen',
    deleteFolderCopy: '„{name}" und alles darin löschen? Das lässt sich nicht rückgängig machen.',
    deleteMemoryCopy: '„{name}" löschen? Das lässt sich nicht rückgängig machen.',
    deleteFailed: 'Konnte nicht gelöscht werden. Bitte versuche es erneut.',
  },
}

import { ref, type Ref } from 'vue'

export const SUPPORTED_LOCALES: SupportedLocale[] = ['en', 'de']
// Endonyms — a language is named in its own tongue regardless of the current UI locale.
export const LOCALE_LABELS: Record<SupportedLocale, string> = { en: 'English', de: 'Deutsch' }

/** The user's language choice: a concrete language, or "system" — follow the OS (the default). */
export type LocalePreference = 'system' | SupportedLocale
export const LOCALE_PREFERENCES: LocalePreference[] = ['system', 'en', 'de']

const LOCALE_STORAGE_KEY = 'veyq:locale'

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

/** The OS language mapped onto a supported locale (anything not German → English). */
function systemLocale(): SupportedLocale {
  const nav = typeof navigator !== 'undefined' ? navigator.language.toLowerCase() : 'en'
  return nav.startsWith('de') ? 'de' : 'en'
}

function storedPreference(): LocalePreference {
  const stored = storage()?.getItem(LOCALE_STORAGE_KEY)
  return stored === 'en' || stored === 'de' || stored === 'system' ? stored : 'system'
}

/** The active locale for a preference — "system" resolves to the OS language. */
export function effectiveLocale(pref: LocalePreference): SupportedLocale {
  return pref === 'system' ? systemLocale() : pref
}

// The reactive language preference (mirrors `useThemeState`'s `themePreference`) — default "system".
const localePref = ref<LocalePreference>(storedPreference())
/** The reactive language preference ("system" | "en" | "de"). */
export function localePreference(): Ref<LocalePreference> {
  return localePref
}

export const i18n = createI18n({
  legacy: false,
  locale: effectiveLocale(localePref.value),
  fallbackLocale: 'en',
  messages: { en, de },
})

/** Set the language preference (a concrete language, or "system") and remember it. */
export function setLocalePreference(pref: LocalePreference): void {
  localePref.value = pref
  storage()?.setItem(LOCALE_STORAGE_KEY, pref)
  i18n.global.locale.value = effectiveLocale(pref)
}

// Live-follow the OS language while on "system" — the browser fires `languagechange` when it changes.
if (typeof window !== 'undefined') {
  window.addEventListener('languagechange', () => {
    if (localePref.value === 'system') i18n.global.locale.value = systemLocale()
  })
}
