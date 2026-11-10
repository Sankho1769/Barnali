import type { SessionUser } from '~/types/api'

export function useAuth() {
  const user = useState<SessionUser | null>('barnali-session-user', () => null)
  const checked = useState('barnali-session-checked', () => false)
  const api = useApi()

  async function refresh() {
    try {
      user.value = await api.request<SessionUser>('/auth/me')
    } catch {
      user.value = null
    } finally {
      checked.value = true
    }
    return user.value
  }

  async function login(email: string, password: string) {
    user.value = await api.request<SessionUser>('/auth/login', {
      method: 'POST',
      body: { email, password }
    })
    checked.value = true
    return user.value
  }

  async function logout() {
    await api.request('/auth/logout', { method: 'POST' })
    user.value = null
    checked.value = true
  }

  return { user, checked, refresh, login, logout }
}
