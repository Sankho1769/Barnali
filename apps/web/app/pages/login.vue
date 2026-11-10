<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
definePageMeta({ layout: false })
useSeoMeta({ title: 'Sign in · BARNALI', robots: 'noindex, nofollow' })

const route = useRoute()
const auth = useAuth()
const email = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref('')

async function submit() {
  if (!email.value.trim() || !password.value) {
    errorMessage.value = 'Enter your email address and password.'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    const user = await auth.login(email.value.trim(), password.value)
    if (user?.mustChangePassword) return await navigateTo('/account/password')
    const next = typeof route.query.next === 'string' && route.query.next.startsWith('/') && !route.query.next.startsWith('//') ? route.query.next : '/'
    await navigateTo(next)
  } catch (cause: unknown) {
    errorMessage.value = getRequestError(cause, 'We could not sign you in. Check your details and try again.')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-story">
      <BarnaliBrand tagline />
      <div class="auth-story__content">
        <div class="auth-story__tag">Ideas · People · Perspectives · Together</div>
        <h1>Knowledge grows when people <em>talk.</em></h1>
        <p>Ask questions, share experiences and learn from a community of curious minds.</p>
      </div>
      <div class="auth-story__tag auth-story__tag--bottom">A thoughtful place to belong</div>
    </section>
    <section class="auth-panel-wrap">
      <div class="auth-panel">
        <div class="auth-panel__head">
          <BarnaliBrand compact />
          <div><h2>Welcome back</h2><p>Sign in to continue your conversations.</p></div>
        </div>
        <div v-if="route.query.passwordChange === 'required'" class="notice" role="status">Set a new password before using your administrator account.</div>
        <div v-if="errorMessage" class="notice notice--error" role="alert">{{ errorMessage }}</div>
        <form class="auth-form" @submit.prevent="submit">
          <div class="form-field">
            <label for="login-email">Email address</label>
            <input id="login-email" v-model="email" class="input" type="email" autocomplete="username" required maxlength="254">
          </div>
          <div class="form-field">
            <label for="login-password">Password</label>
            <div class="password-wrap">
              <input id="login-password" v-model="password" class="input input--with-action" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required maxlength="128">
              <button class="password-toggle" type="button" :aria-label="showPassword ? 'Hide password' : 'Show password'" :aria-pressed="showPassword" @click="showPassword = !showPassword">
                <IconGlyph :name="showPassword ? 'eye-off' : 'eye'" />
              </button>
            </div>
          </div>
          <div class="auth-form__row">
            <span>Secure session</span>
            <NuxtLink to="/forgot-password">Forgot password?</NuxtLink>
          </div>
          <button class="btn" type="submit" :disabled="submitting">{{ submitting ? 'Signing in…' : 'Sign in →' }}</button>
        </form>
        <div class="auth-divider" />
        <p class="auth-form__row" style="justify-content:center">Don’t have an account? <NuxtLink to="/register">Create account →</NuxtLink></p>
      </div>
    </section>
  </main>
</template>
