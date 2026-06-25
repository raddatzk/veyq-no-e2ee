# Konto-Schlüssel & Wiederherstellung

Eine verständliche Ergänzung zu [Sind meine Daten sicher?](sind-meine-daten-sicher.md). Weil veyq
Ende-zu-Ende-verschlüsselt ist, **schützen deine Schlüssel — nicht der Server — deine Daten.** So
funktioniert das, ganz ohne Krypto-Vorwissen.

**Ein paar Begriffe, die dir begegnen:**
- **Schlüssel** — ein Geheimnis, das Daten sperrt/entsperrt.
- **Master-Schlüssel** — der eine Schlüssel, der alles andere schützt. Alle deine Erinnerungen hängen
  daran.
- **Secret Key** — ein hochzufälliger Code in deinem **Notfall-Kit**, kombiniert mit deinem Passwort.
  Wird nie an den Server gesendet.
- **Wiederherstellungscode** — ein einmaliger Backup-Code, der dein Konto allein entsperren kann.

## Ein Master-Schlüssel, mehrere Wege ihn zu öffnen

Es gibt **einen Master-Schlüssel**, und er ist **separat durch jede eingerichtete Methode** gesperrt:

```
Master-Schlüssel ─┬─ geöffnet durch Passwort + Secret Key   (immer eingerichtet)
                  ├─ geöffnet durch deinen Passkey (Face ID / Fingerabdruck)
                  ├─ geöffnet durch deinen Wiederherstellungscode
                  └─ geöffnet durch dieses vertraute Gerät
```

Jede einzelne öffnet **denselben** Master-Schlüssel, der **alle** deine Inhalte entsperrt. Eine Methode
hinzuzufügen oder zu entfernen fügt nur ein Schloss hinzu oder entfernt es — **deine Erinnerungen werden
nie neu verschlüsselt.**

## Anmelden

- **Dein eigenes Gerät** — entsperrt mit Face ID / Fingerabdruck; nichts zu tippen.
- **Ein synchronisierter Passkey** — auf einem neuen Gerät entsperrt dich ein Passkey aus iCloud/Google.
- **Ein anderes Gerät bestätigt es** — dein altes Handy gibt das neue frei, wie das Verknüpfen eines
  Messengers.
- **Kaltstart** — nichts zur Hand? Melde dich mit **Passwort + dem Secret Key** aus deinem Notfall-Kit
  an.

Der Secret Key wird nur in diesem letzten Fall gebraucht. Warum überhaupt? Dein Passwort wird zum
Anmelden an den Server gesendet — käme der Master-Schlüssel *allein* aus dem Passwort, könnte der Server
ihn öffnen. Der Secret Key (nie gesendet) schließt diese Lücke.

## Was der Server tatsächlich speichert

Nur die **gesperrten** Kopien deines Master-Schlüssels — nie etwas, das sie öffnet. Das, was eine Kopie
entsperrt, entsteht für einen Moment beim Anmelden auf deinem Gerät und wird dann verworfen. Es wird nie
gespeichert und nie gesendet. Genau das hält den Server blind.

## Wenn du den Zugang verlierst — drei Wege zurück

- **Ein anderes Gerät** — noch eines angemeldet? Setze einfach ein neues Passwort.
- **Notfall-Kit** — dein Secret Key + Passwort bringen dich auf einem neuen Gerät rein.
- **Wiederherstellungscode** — der letzte Ausweg: er entsperrt dein Konto allein und gibt dir dann ein
  **frisches Kit** (neuer Secret Key + neuer Wiederherstellungscode). Er ist **einmalig**.

**Warum ein vergessenes Passwort deine Erinnerungen nicht verliert:** alles hängt an deinem *Schlüssel*,
nicht am Passwort. Hol den Schlüssel über **irgendeinen** Weg zurück, und alles kommt auf einmal wieder.

**Das Einzige, was wir nicht rückgängig machen können:** verlierst du **jeden** Weg — kein Gerät, kein
Notfall-Kit, keinen Wiederherstellungscode — sind deine Inhalte weg. Es gibt **keine Hintertür und keine
Schlüsselhinterlegung**: Dasselbe, was *uns* am Lesen hindert, hindert uns am Retten.
