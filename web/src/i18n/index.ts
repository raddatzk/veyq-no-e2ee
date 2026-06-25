import { createI18n } from 'vue-i18n'

// Focused message catalog for the setup slice (EN + DE). The full legacy catalog arrives as later UI
// increments land; keys here mirror the legacy keys 1:1 so the reused views need no string changes.
export type SupportedLocale = 'en' | 'de'

const en = {
  common: { email: 'Email', password: 'Password', unreachable: 'This instance can’t be reached right now. Please try again.' },
  setup: {
    title: 'Set up this instance',
    sub: 'Create the first owner account. All keys are generated on this device — the server only ever sees ciphertext.',
    instanceName: 'Instance name',
    displayName: 'Your name',
    submit: 'Create owner',
    submitting: 'Creating…',
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
  login: { title: 'Sign in', stub: 'Login is not available yet — it arrives in a later change.' },
}

const de: typeof en = {
  common: { email: 'E-Mail', password: 'Passwort', unreachable: 'Diese Instanz ist gerade nicht erreichbar. Bitte erneut versuchen.' },
  setup: {
    title: 'Diese Instanz einrichten',
    sub: 'Lege das erste Besitzer-Konto an. Alle Schlüssel entstehen auf diesem Gerät — der Server sieht nur Chiffretext.',
    instanceName: 'Name der Instanz',
    displayName: 'Dein Name',
    submit: 'Besitzer anlegen',
    submitting: 'Wird angelegt…',
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
  login: { title: 'Anmelden', stub: 'Anmeldung ist noch nicht verfügbar — sie kommt in einer späteren Änderung.' },
}

function initialLocale(): SupportedLocale {
  const nav = typeof navigator !== 'undefined' ? navigator.language.toLowerCase() : 'en'
  return nav.startsWith('de') ? 'de' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: initialLocale(),
  fallbackLocale: 'en',
  messages: { en, de },
})
