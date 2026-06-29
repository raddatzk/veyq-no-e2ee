package com.veyq.app.i18n

/**
 * Pure string resolution for the runtime i18n (cr-0043, the Android parity of Apple's `VeyqKit.Localization`
 * from cr-0035/0037). The table + lookup live here as plain Kotlin — no Android SDK, no Compose — so they are
 * JVM-unit-testable (`:app:testDebugUnitTest`, the analog of `VeyqKitTests/LocalizationTests`). The reactive
 * active locale lives in [L10n].
 *
 * English source + German translation; an unknown locale falls back to English; an unknown key resolves to
 * itself. The English values are kept **byte-identical** to the literals they replaced so the existing live
 * UI round-trip (which finds nodes by their English text / label-derived test tags) keeps passing.
 */
object Localization {
    /** Look up [key] for [locale], falling back to English, then to the key itself. */
    fun string(locale: String, key: String): String =
        strings[locale]?.get(key) ?: strings["en"]?.get(key) ?: key

    /**
     * Resolve a language preference ("system" / "en" / "de") to a concrete locale. "system" follows the OS:
     * German if the system language is German, otherwise English (we ship en + de).
     */
    fun resolve(preference: String, systemLanguageCode: String?): String {
        if (preference == "en" || preference == "de") return preference
        return if (systemLanguageCode == "de") "de" else "en"
    }

    val strings: Map<String, Map<String, String>> = mapOf(
        "en" to mapOf(
            // common
            "common.name" to "Name",
            "common.create" to "Create",
            "common.cancel" to "Cancel",
            "common.rename" to "Rename",
            "common.delete" to "Delete",
            "common.done" to "Done",
            "common.differentInstance" to "Use a different instance",
            // shell / memory browse
            "home.spaces" to "SPACES",
            "home.personal" to "Personal",
            "home.memories" to "Memories",
            "home.folders" to "Folders",
            "home.noMemories" to "No memories yet",
            "home.noFolders" to "No folders yet",
            "home.newMemory" to "New memory",
            "home.newFolder" to "New folder",
            "home.signOut" to "Sign out",
            "home.settings" to "Settings",
            "home.spacesNav" to "Spaces",
            "home.create" to "Create",
            "home.back" to "Back",
            "home.deviceTrusted" to "This device is trusted — next time you’ll unlock with just your password.",
            "home.stopTrusting" to "Stop trusting this device",
            "home.deleteTitle" to "Delete “%s”?",
            "home.deleteBody" to "This removes it and everything inside it.",
            // settings
            "settings.title" to "Settings",
            "settings.langAppearance" to "Language & appearance",
            "settings.appearance" to "Appearance",
            "settings.system" to "System",
            "settings.light" to "Light",
            "settings.dark" to "Dark",
            "settings.language" to "Language",
            "settings.account" to "Account",
            "settings.instance" to "Instance",
            // connect
            "connect.title" to "Connect to your instance",
            "connect.subtitle" to "Enter the address of your veyq server.",
            "connect.url" to "Instance URL",
            "connect.action" to "Connect",
            "connect.setupNeeded" to "This instance still needs first-run setup. Finish it in a web browser, then connect here.",
            // sign in
            "signIn.title" to "Sign in",
            "signIn.email" to "Email",
            "signIn.password" to "Password",
            "signIn.kitImport" to "Import your Emergency Kit (fills email + Secret Key)",
            "signIn.kitImported" to "Emergency Kit imported — your Secret Key is ready for unlock.",
            // unlock
            "unlock.title" to "Unlock your vault",
            "unlock.secretKey" to "Secret Key",
            "unlock.secretKeyHint" to "From your Emergency Kit. Used with your password to unlock — never sent.",
            "unlock.kitImport" to "Import your Emergency Kit to fill the Secret Key",
            "unlock.action" to "Unlock",
            // emergency-kit import / scanner
            "kit.readError" to "Couldn’t read an Emergency Kit from that file.",
            "kit.scanTitle" to "Scan your Emergency Kit",
            "kit.scanHint" to "Point the camera at the QR code on your kit.",
            "kit.cameraDenied" to "Camera access is off. Allow it in settings, or upload the kit instead.",
            "kit.requesting" to "Requesting camera access…",
            "kit.uploadKit" to "Upload kit",
            "kit.scanQr" to "Scan QR",
            // device-security chooser
            "device.prompt" to "How should this device unlock next time?",
            "device.notAvailable" to "Not available",
            "device.secretEachTime.title" to "Secret Key each time",
            "device.secretEachTime.sub" to "Most private — enter your password and Secret Key every time.",
            "device.onlyPassword.title" to "Only my password",
            "device.onlyPassword.sub" to "Trust this device: next time just your password, no Secret Key.",
            "device.biometric.title" to "Fingerprint / Face",
            "device.biometric.sub" to "Unlock with a touch — no password.",
            "device.noLogin.title" to "Open straight in",
            "device.noLogin.sub" to "No prompt at all — relies only on your device’s lock screen.",
        ),
        "de" to mapOf(
            // common
            "common.name" to "Name",
            "common.create" to "Erstellen",
            "common.cancel" to "Abbrechen",
            "common.rename" to "Umbenennen",
            "common.delete" to "Löschen",
            "common.done" to "Fertig",
            "common.differentInstance" to "Andere Instanz verwenden",
            // shell / memory browse
            "home.spaces" to "BEREICHE",
            "home.personal" to "Persönlich",
            "home.memories" to "Erinnerungen",
            "home.folders" to "Ordner",
            "home.noMemories" to "Noch keine Erinnerungen",
            "home.noFolders" to "Noch keine Ordner",
            "home.newMemory" to "Neue Erinnerung",
            "home.newFolder" to "Neuer Ordner",
            "home.signOut" to "Abmelden",
            "home.settings" to "Einstellungen",
            "home.spacesNav" to "Bereiche",
            "home.create" to "Erstellen",
            "home.back" to "Zurück",
            "home.deviceTrusted" to "Diesem Gerät wird vertraut — beim nächsten Mal entsperrst du nur mit deinem Passwort.",
            "home.stopTrusting" to "Diesem Gerät nicht mehr vertrauen",
            "home.deleteTitle" to "„%s“ löschen?",
            "home.deleteBody" to "Das entfernt es und alles darin.",
            // settings
            "settings.title" to "Einstellungen",
            "settings.langAppearance" to "Sprache & Darstellung",
            "settings.appearance" to "Darstellung",
            "settings.system" to "System",
            "settings.light" to "Hell",
            "settings.dark" to "Dunkel",
            "settings.language" to "Sprache",
            "settings.account" to "Konto",
            "settings.instance" to "Instanz",
            // connect
            "connect.title" to "Mit deiner Instanz verbinden",
            "connect.subtitle" to "Gib die Adresse deines veyq-Servers ein.",
            "connect.url" to "Instanz-URL",
            "connect.action" to "Verbinden",
            "connect.setupNeeded" to "Diese Instanz benötigt noch die Ersteinrichtung. Schließe sie im Webbrowser ab und verbinde dich dann hier.",
            // sign in
            "signIn.title" to "Anmelden",
            "signIn.email" to "E-Mail",
            "signIn.password" to "Passwort",
            "signIn.kitImport" to "Notfall-Kit importieren (füllt E-Mail + Secret Key)",
            "signIn.kitImported" to "Notfall-Kit importiert — dein Secret Key ist bereit zum Entsperren.",
            // unlock
            "unlock.title" to "Tresor entsperren",
            "unlock.secretKey" to "Secret Key",
            "unlock.secretKeyHint" to "Aus deinem Notfall-Kit. Wird mit deinem Passwort zum Entsperren genutzt — nie gesendet.",
            "unlock.kitImport" to "Notfall-Kit importieren, um den Secret Key zu füllen",
            "unlock.action" to "Entsperren",
            // emergency-kit import / scanner
            "kit.readError" to "Aus dieser Datei ließ sich kein Notfall-Kit lesen.",
            "kit.scanTitle" to "Notfall-Kit scannen",
            "kit.scanHint" to "Richte die Kamera auf den QR-Code deines Kits.",
            "kit.cameraDenied" to "Kamerazugriff ist aus. Erlaube ihn in den Einstellungen oder lade das Kit hoch.",
            "kit.requesting" to "Kamerazugriff wird angefragt…",
            "kit.uploadKit" to "Kit hochladen",
            "kit.scanQr" to "QR scannen",
            // device-security chooser
            "device.prompt" to "Wie soll dieses Gerät beim nächsten Mal entsperren?",
            "device.notAvailable" to "Nicht verfügbar",
            "device.secretEachTime.title" to "Jedes Mal mit Secret Key",
            "device.secretEachTime.sub" to "Am privatesten — gib jedes Mal Passwort und Secret Key ein.",
            "device.onlyPassword.title" to "Nur mein Passwort",
            "device.onlyPassword.sub" to "Diesem Gerät vertrauen: beim nächsten Mal nur dein Passwort, kein Secret Key.",
            "device.biometric.title" to "Fingerabdruck / Gesicht",
            "device.biometric.sub" to "Mit einer Berührung entsperren — kein Passwort.",
            "device.noLogin.title" to "Direkt öffnen",
            "device.noLogin.sub" to "Gar keine Abfrage — verlässt sich nur auf die Sperre deines Geräts.",
        ),
    )
}
