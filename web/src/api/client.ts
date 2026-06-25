// Thin factory over the contracts-generated clients. The whole app is same-origin, so the basePath is the
// in-memory `session.apiBase` ('' → relative `/api/...`). Keeping construction here means views/features
// depend on our typed surface, never on the generator's runtime details. The setup, auth and account specs
// generate into separate client packages (`./generated`, `./generated/auth`, `./generated/account`).
import { Configuration, SetupApi } from './generated'
import {
  Configuration as AuthConfiguration,
  LoginApi,
  SessionApi,
  TokenApi,
} from './generated/auth'
import { AccountApi, Configuration as AccountConfiguration } from './generated/account'
import { session } from '../app/session'

export function setupApi(): SetupApi {
  return new SetupApi(new Configuration({ basePath: session.apiBase }))
}

// Auth clients send the access token as the bearer credential (used by the authed endpoints, e.g. logout).
function authConfig(): AuthConfiguration {
  return new AuthConfiguration({
    basePath: session.apiBase,
    accessToken: () => session.accessToken ?? '',
  })
}

export function loginApi(): LoginApi {
  return new LoginApi(authConfig())
}

export function tokenApi(): TokenApi {
  return new TokenApi(authConfig())
}

export function sessionApi(): SessionApi {
  return new SessionApi(authConfig())
}

// The account client also sends the access token as the bearer credential (authed key-material read).
export function accountApi(): AccountApi {
  return new AccountApi(
    new AccountConfiguration({ basePath: session.apiBase, accessToken: () => session.accessToken ?? '' }),
  )
}
