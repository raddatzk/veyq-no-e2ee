export type SettingsSubjectType =
  | 'SERVER'
  | 'USER'
  | 'TENANT'
  | 'TENANT_USER'
  | 'GROUP'
  | 'FOLDER'
  | 'ALBUM'

export type SettingsLocation = {
  path: string
  displayName: string
  label: string
}

export type SettingsSubject = {
  type: SettingsSubjectType
  displayName: string
  label: string
}

export type SettingsBreadcrumb = {
  type: SettingsSubjectType
  path: string
  displayName: string
  label: string
}

export type SettingsResponse = {
  location: SettingsLocation
  subject: SettingsSubject
  breadcrumbs: SettingsBreadcrumb[]
}

export type ServerSettingsResponse = {
  displayName: string
  userManagementAvailable: boolean
}

export type UserSettingsResponse = {
  userId: number
  email: string
  displayName: string
}

export type UpdateUserSettingsRequest = {
  displayName: string
  email: string
}

export type ChangePasswordRequest = {
  currentPassword: string
  newPassword: string
}
