export default defineNuxtRouteMiddleware(async (to) => {
  const auth = useAuth()
  const user = auth.checked.value ? auth.user.value : await auth.refresh()
  if (!user) return navigateTo({ path: '/login', query: { next: to.fullPath } })
})
