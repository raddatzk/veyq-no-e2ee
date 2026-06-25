// Thin factory over the contracts-generated client. The whole app is same-origin, so the basePath is the
// in-memory `session.apiBase` ('' → relative `/api/...`). Keeping construction here means views/features
// depend on our typed surface, never on the generator's runtime details.
import { Configuration, SetupApi } from './generated'
import { session } from '../app/session'

export function setupApi(): SetupApi {
  return new SetupApi(new Configuration({ basePath: session.apiBase }))
}
