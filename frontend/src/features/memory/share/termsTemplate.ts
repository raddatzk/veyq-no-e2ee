// Ready-made Terms-of-Service clauses a Tenant Owner can assemble into their group's ToS (the "template").
// Each clause is a self-contained Markdown block in de/en; the owner toggles the ones they want and edits
// freely. Ported from the legacy clause set. The {contact} placeholder in `contact` is filled in by the owner.

export type TermsClause = { id: string; label: { de: string; en: string }; markdown: { de: string; en: string } }

export const TERMS_CLAUSES: TermsClause[] = [
  {
    id: 'purpose',
    label: { de: 'Zweck der Instanz', en: 'Purpose of the instance' },
    markdown: {
      de: '## Zweck dieser Instanz\n\nDiese veyq-Instanz ist für private Memories, Reisen, Events und persönliche Momente gedacht. Sie ist kein allgemeiner Filesharing-Dienst und keine öffentliche Plattform.',
      en: '## Purpose of this instance\n\nThis veyq instance is intended for private Memories, trips, events, and personal moments. It is not a general filesharing service or public platform.',
    },
  },
  {
    id: 'privacy',
    label: { de: 'Datenschutz und Privatsphäre', en: 'Privacy and personal data' },
    markdown: {
      de: '## Datenschutz und Privatsphäre\n\nHochgeladene Inhalte können personenbezogene Daten enthalten. Lade nur Inhalte hoch, die für den jeweiligen Share bestimmt sind, und achte auf die Privatsphäre der abgebildeten oder erwähnten Personen.',
      en: '## Privacy and personal data\n\nUploaded content may contain personal data. Upload only content intended for the relevant share, and respect the privacy of people shown or mentioned.',
    },
  },
  {
    id: 'rights',
    label: { de: 'Rechte an Inhalten', en: 'Rights to content' },
    markdown: {
      de: '## Rechte an Inhalten\n\nDu darfst nur Fotos, Videos, Texte und andere Inhalte hochladen, die du verwenden und mit den vorgesehenen Personen teilen darfst.',
      en: '## Rights to content\n\nOnly upload photos, videos, text, and other content that you may use and share with the intended people.',
    },
  },
  {
    id: 'children',
    label: { de: 'Kinder und sensible Personen', en: 'Children and sensitive people' },
    markdown: {
      de: '## Kinder und sensible Informationen\n\nBei Kindern, Gesundheitsdaten, privaten Adressen, Schulen, Aufenthaltsorten und anderen sensiblen Informationen ist besondere Vorsicht erforderlich.',
      en: '## Children and sensitive information\n\nUse particular care with children, health data, private addresses, schools, locations, and other sensitive information.',
    },
  },
  {
    id: 'illegal',
    label: { de: 'Illegale Inhalte', en: 'Illegal content' },
    markdown: {
      de: '## Nicht erlaubte Inhalte\n\nNicht erlaubt sind insbesondere rechtswidrige Inhalte, CSAM, nicht-einvernehmliche intime Inhalte, Gewaltaufrufe, Doxxing, Stalking, Belästigung, Betrug, Malware, Terrorpropaganda sowie Inhalte, die Rechte Dritter verletzen.',
      en: '## Prohibited content\n\nIllegal content is not allowed, including CSAM, non-consensual intimate content, calls for violence, doxxing, stalking, harassment, fraud, malware, terrorist propaganda, and content that violates third-party rights.',
    },
  },
  {
    id: 'sharing',
    label: { de: 'Share-Links', en: 'Share links' },
    markdown: {
      de: '## Share-Links\n\nShare-Links dürfen nur an die vorgesehenen Personen weitergegeben werden. Inhalte aus einem Share sollen nicht ohne Zustimmung der betroffenen Personen weiterveröffentlicht werden.',
      en: '## Share links\n\nShare links may only be forwarded to the intended people. Content from a share should not be republished without consent from the affected people.',
    },
  },
  {
    id: 'uploads',
    label: { de: 'Uploads durch Gäste', en: 'Guest uploads' },
    markdown: {
      de: '## Uploads durch Gäste\n\nWenn du über einen Share Inhalte hochlädst, bestätigst du, dass du dazu berechtigt bist. Uploads können je nach Freigabe sichtbar sein und dem angegebenen Namen zugeordnet werden.',
      en: '## Guest uploads\n\nWhen uploading through a share, you confirm that you are allowed to do so. Depending on the share, uploads may be visible and attributed to the name you provide.',
    },
  },
  {
    id: 'availability',
    label: { de: 'Verfügbarkeit', en: 'Availability' },
    markdown: {
      de: '## Verfügbarkeit\n\nDer Betrieb dieser Instanz erfolgt ohne Garantie für dauerhafte Erreichbarkeit, Backups, Performance oder Datenwiederherstellung.',
      en: '## Availability\n\nThis instance is operated without guarantees for permanent availability, backups, performance, or data recovery.',
    },
  },
  {
    id: 'moderation',
    label: { de: 'Prüfung und Entfernung', en: 'Review and removal' },
    markdown: {
      de: '## Prüfung und Entfernung\n\nDer Betreiber kann Inhalte oder Freigaben prüfen, entfernen oder deaktivieren, wenn sie gegen diese Hinweise verstoßen, Rechte Dritter betreffen oder rechtliche Risiken verursachen.',
      en: '## Review and removal\n\nThe operator may review, remove, or disable content or shares if they violate these terms, affect third-party rights, or create legal risk.',
    },
  },
  {
    id: 'external',
    label: { de: 'Externe Dienste', en: 'External services' },
    markdown: {
      de: '## Externe Dienste\n\nKarten, Musiklinks oder andere externe Dienste können eigene Datenschutz- und Nutzungsbedingungen haben.',
      en: '## External services\n\nMaps, music links, and other external services may have their own privacy policies and terms.',
    },
  },
  {
    id: 'contact',
    label: { de: 'Kontakt und Löschung', en: 'Contact and deletion' },
    markdown: {
      de: '## Kontakt und Löschung\n\nWenn du möchtest, dass Inhalte mit Bezug zu dir entfernt werden, oder wenn du Fragen zu Datenschutz, Freigaben oder hochgeladenen Inhalten hast, wende dich bitte an: {contact}.',
      en: '## Contact and deletion\n\nIf you want content related to you removed, or if you have questions about privacy, shares, or uploaded content, contact: {contact}.',
    },
  },
]
