// App-wide session state. In this CR only `apiBase` matters: the app is same-origin, so it talks to
// `/api/...` on the host that served the page (Vite proxies to the backend in dev). The unlocked-keys /
// active-space machinery from the legacy app arrives with the login CR; setup needs none of it.
import { reactive } from 'vue'

export const session = reactive({
  // same-origin: '' → fetches hit `/api/...` on the serving instance
  apiBase: '',
})
