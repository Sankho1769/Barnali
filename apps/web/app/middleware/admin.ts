export default defineNuxtRouteMiddleware(async (to) => {
  const auth = useAuth()
  const user = auth.checked.value ? auth.user.value : await auth.refresh()
  if (!user) {
    return navigateTo({ path: '/admin/login', query: { next: to.fullPath } })
  }
  if (user.role === 'MEMBER') return navigateTo('/admin/access-denied')
  if (user.mustChangePassword) return navigateTo('/account/password')
})
