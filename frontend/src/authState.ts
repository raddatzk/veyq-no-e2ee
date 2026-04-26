import { reactive } from 'vue'

export const authState = reactive({
  loading: true,
  isAuthenticated: false,
  username: ''
})
