// The Emergency Kit — a saveable artifact carrying everything needed to sign in again EXCEPT the password
// (which stays only in the user's head). It's human-readable (printed text), camera-readable (a QR), and
// machine-readable (an embedded payload), so it can be re-entered, scanned, or imported as a file later.
// The Secret Key is sensitive — this is the user's recovery secret, so the kit must be stored safely.
import { jsPDF } from 'jspdf'
import QRCode from 'qrcode'

export type KitData = { instance: string; email: string; secretKey: string }

const PREFIX = 'veyqkit:'

function b64url(s: string): string {
  return btoa(unescape(encodeURIComponent(s))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function fromB64url(s: string): string {
  return decodeURIComponent(escape(atob(s.replace(/-/g, '+').replace(/_/g, '/'))))
}

/** The compact, machine-readable kit token (goes in the QR and is embedded in the PDF). */
export function kitPayload(data: KitData): string {
  return PREFIX + b64url(JSON.stringify({ v: 1, instance: data.instance, email: data.email, sk: data.secretKey }))
}

/** Parse a kit token out of any text — a scanned QR string, a `.veyqkit` file, or the bytes of the kit PDF. */
export function parseKit(text: string): KitData | null {
  const m = new RegExp(PREFIX + '([A-Za-z0-9_-]+)').exec(text)
  if (!m) return null
  try {
    const o = JSON.parse(fromB64url(m[1]))
    if (o.v !== 1 || !o.email || !o.sk) return null
    return { instance: o.instance ?? '', email: o.email, secretKey: o.sk }
  } catch {
    return null
  }
}

/** Build + download the Emergency Kit PDF: human text + QR + an embedded payload (in the PDF metadata). */
export async function downloadKitPdf(data: KitData, displayName: string): Promise<void> {
  const payload = kitPayload(data)
  const qr = await QRCode.toDataURL(payload, { margin: 1, width: 320 })
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  // Embed the machine-readable payload in the document metadata — uncompressed in the PDF bytes, so a later
  // file import can recover it by scanning the file text without decoding the QR.
  doc.setProperties({ title: 'veyq Emergency Kit', keywords: payload })

  doc.setFontSize(22); doc.text('veyq — Emergency Kit', 56, 70)
  doc.setFontSize(11); doc.setTextColor(90)
  doc.text('Keep this safe. With your Secret Key + your password you can always sign in again.', 56, 96)
  doc.setTextColor(20); doc.setFontSize(12)
  const rows: [string, string][] = [['Name', displayName], ['Instance', data.instance || '(same as the website)'], ['Email', data.email]]
  let y = 140
  for (const [k, v] of rows) { doc.setFont('helvetica', 'bold'); doc.text(k, 56, y); doc.setFont('helvetica', 'normal'); doc.text(v, 160, y); y += 26 }
  doc.setFont('helvetica', 'bold'); doc.text('Secret Key', 56, y); doc.setFont('courier', 'normal'); doc.setFontSize(11)
  doc.text(doc.splitTextToSize(data.secretKey, 360), 160, y); y += 50

  doc.addImage(qr, 'PNG', 56, y, 150, 150)
  doc.setFont('helvetica', 'normal'); doc.setFontSize(10); doc.setTextColor(90)
  doc.text('Scan this code, or import this PDF, to fill in your email + Secret Key on a new device.', 220, y + 80, { maxWidth: 300 })

  doc.save(`veyq-emergency-kit-${data.email}.pdf`)
}
