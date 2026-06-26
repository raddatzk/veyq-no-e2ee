import { createI18n } from 'vue-i18n'

// Focused message catalog for the setup slice (EN + DE). The full legacy catalog arrives as later UI
// increments land; keys here mirror the legacy keys 1:1 so the reused views need no string changes.
export type SupportedLocale = 'en' | 'de'

const en = {
  common: { email: 'Email', password: 'Password', cancel: 'Cancel', unreachable: 'This instance can’t be reached right now. Please try again.' },
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
    deviceNote: 'This device is trusted — unlock with just your password.',
    anotherWay: 'Use my Secret Key instead',
    deviceUnavailable: 'This device is no longer trusted. Enter your Secret Key.',
    trustDevice: 'Trust this device',
    trustDeviceHint: 'Next time, unlock on this device with just your password — your Secret Key stays in your kit.',
    trustFailed: 'Couldn’t trust this device — you unlocked fine, but you’ll need your Secret Key next time.',
    untrust: 'Untrust this device',
  },
}

const de: typeof en = {
  common: { email: 'E-Mail', password: 'Passwort', cancel: 'Abbrechen', unreachable: 'Diese Instanz ist gerade nicht erreichbar. Bitte erneut versuchen.' },
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
    deviceNote: 'Diesem Gerät wird vertraut — entsperre nur mit deinem Passwort.',
    anotherWay: 'Stattdessen meinen Secret Key verwenden',
    deviceUnavailable: 'Diesem Gerät wird nicht mehr vertraut. Gib deinen Secret Key ein.',
    trustDevice: 'Diesem Gerät vertrauen',
    trustDeviceHint: 'Beim nächsten Mal entsperrst du auf diesem Gerät nur mit deinem Passwort — dein Secret Key bleibt im Kit.',
    trustFailed: 'Konnte diesem Gerät nicht vertrauen — entsperrt hat es geklappt, aber beim nächsten Mal brauchst du deinen Secret Key.',
    untrust: 'Vertrauen aufheben',
  },
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
