type RequestOptions = Parameters<typeof $fetch>[1]

export function useApi() {
  const config = useRuntimeConfig()
  const baseURL = config.public.apiBase

  async function csrfToken(): Promise<string> {
    if (import.meta.client) {
      const cached = useCookie<string | null>('XSRF-TOKEN').value
      if (cached) return decodeURIComponent(cached)
    }
    const result = await $fetch<{ token: string }>('/auth/csrf', {
      baseURL,
      credentials: 'include'
    })
    return result.token
  }

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = String(options.method || 'GET').toUpperCase()
    const headers = new Headers(options.headers as HeadersInit | undefined)
    if (import.meta.server) {
      const incoming = useRequestHeaders(['cookie']).cookie
      if (incoming) headers.set('cookie', incoming)
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      headers.set('X-XSRF-TOKEN', await csrfToken())
    }
    return await $fetch<T>(path, {
      ...options,
      baseURL,
      credentials: 'include',
      headers
    } as RequestOptions)
  }

  return { request, baseURL }
}
