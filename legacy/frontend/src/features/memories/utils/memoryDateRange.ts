const fullDateFormatter = new Intl.DateTimeFormat('de-DE', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric'
})

export function formatMemoryDateRange(startDate: string | null, endDate: string | null): string {
  if (!startDate || !endDate) return 'Datum fehlt'
  if (startDate === endDate) return fullDateFormatter.format(parseLocalDate(startDate))
  return `${fullDateFormatter.format(parseLocalDate(startDate))} - ${fullDateFormatter.format(parseLocalDate(endDate))}`
}

export function isValidMemoryDateRange(startDate: string, endDate: string): boolean {
  return Boolean(startDate && endDate && endDate >= startDate)
}

function parseLocalDate(value: string): Date {
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year, month - 1, day)
}
