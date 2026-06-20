import type { PrimeVueConfiguration, PrimeVueLocaleOptions } from 'primevue/config'
import type { Locale } from './i18n'

const primeVueLocales: Record<Locale, PrimeVueLocaleOptions> = {
  de: {
    firstDayOfWeek: 1,
    dayNames: ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'],
    dayNamesShort: ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'],
    dayNamesMin: ['S', 'M', 'D', 'M', 'D', 'F', 'S'],
    monthNames: [
      'Januar',
      'Februar',
      'Maerz',
      'April',
      'Mai',
      'Juni',
      'Juli',
      'August',
      'September',
      'Oktober',
      'November',
      'Dezember'
    ],
    monthNamesShort: [
      'Jan',
      'Feb',
      'Maer',
      'Apr',
      'Mai',
      'Jun',
      'Jul',
      'Aug',
      'Sep',
      'Okt',
      'Nov',
      'Dez'
    ],
    today: 'Heute',
    clear: 'Leeren',
    dateFormat: 'dd.mm.yy',
    weekHeader: 'KW',
    chooseDate: 'Datum waehlen',
    prevMonth: 'Vorheriger Monat',
    nextMonth: 'Naechster Monat',
    chooseMonth: 'Monat waehlen',
    chooseYear: 'Jahr waehlen',
    fileSizeTypes: ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']
  },
  en: {
    firstDayOfWeek: 0,
    dayNames: ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'],
    dayNamesShort: ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'],
    dayNamesMin: ['S', 'M', 'T', 'W', 'T', 'F', 'S'],
    monthNames: [
      'January',
      'February',
      'March',
      'April',
      'May',
      'June',
      'July',
      'August',
      'September',
      'October',
      'November',
      'December'
    ],
    monthNamesShort: [
      'Jan',
      'Feb',
      'Mar',
      'Apr',
      'May',
      'Jun',
      'Jul',
      'Aug',
      'Sep',
      'Oct',
      'Nov',
      'Dec'
    ],
    today: 'Today',
    clear: 'Clear',
    dateFormat: 'mm/dd/yy',
    weekHeader: 'Wk',
    chooseDate: 'Choose date',
    prevMonth: 'Previous month',
    nextMonth: 'Next month',
    chooseMonth: 'Choose month',
    chooseYear: 'Choose year',
    fileSizeTypes: ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB']
  }
}

export function primeVueLocale(locale: Locale) {
  return primeVueLocales[locale]
}

export function applyPrimeVueLocale(config: PrimeVueConfiguration, locale: Locale) {
  config.locale = {
    ...config.locale,
    ...primeVueLocale(locale)
  }
}
