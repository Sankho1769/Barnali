<script setup lang="ts">
import { getRequestError } from '~/utils/errors'
definePageMeta({ layout: false })
useSeoMeta({ title: 'Create account · BARNALI', robots: 'noindex, nofollow' })

const api = useApi()
const displayName = ref('')
const username = ref('')
const email = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

async function submit() {
  errorMessage.value = ''
  successMessage.value = ''
  if (password.value.length < 12) {
    errorMessage.value = 'Use a passphrase with at least 12 characters.'
    return
  }
  submitting.value = true
  try {
    await api.request('/auth/register', {
      method: 'POST',
      body: { displayName: displayName.value.trim(), username: username.value.trim(), email: email.value.trim(), password: password.value }
    })
    successMessage.value = 'Your account is ready. Sign in to start participating.'
  } catch (cause: unknown) {
    errorMessage.value = getRequestError(cause, 'We could not create your account. Check the fields and try again.')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-story">
      <BarnaliBrand />
      <div class="auth-story__content">
        <div class="auth-story__tag">Ideas · People · Perspectives · Together</div>
        <h1>Bring your questions. <em>Leave with new ideas.</em></h1>
        <p>Join thoughtful conversations with people who are curious about the world around them.</p>
      </div>
    </section>
    <section class="auth-panel-wrap">
      <div class="auth-panel">
        <div class="auth-panel__head"><BarnaliBrand compact /><div><h2>Create your account</h2><p>Join BARNALI and take part in the conversation.</p></div></div>
        <div v-if="errorMessage" class="notice notice--error" role="alert">{{ errorMessage }}</div>
        <div v-if="successMessage" class="notice notice--success" role="status">{{ successMessage }} <NuxtLink to="/login">Sign in</NuxtLink></div>
        <form class="auth-form" @submit.prevent="submit">
          <div class="form-field"><label for="register-name">Display name</label><input id="register-name" v-model="displayName" class="input" autocomplete="name" maxlength="80" required></div>
          <div class="form-field"><label for="register-username">Username</label><input id="register-username" v-model="username" class="input" autocomplete="username" minlength="3" maxlength="32" pattern="[A-Za-z0-9_]+" required><span class="muted">Letters, numbers, and underscores.</span></div>
          <div class="form-field"><label for="register-email">Email address</label><input id="register-email" v-model="email" class="input" type="email" autocomplete="email" maxlength="254" required></div>
          <div class="form-field">
            <label for="register-password">Password</label>
            <div class="password-wrap"><input id="register-password" v-model="password" class="input input--with-action" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required><button class="password-toggle" type="button" :aria-label="showPassword ? 'Hide password' : 'Show password'" @click="showPassword = !showPassword"><IconGlyph :name="showPassword ? 'eye-off' : 'eye'" /></button></div>
            <span class="muted">Use at least 12 characters. Your password is never shown to administrators.</span>
          </div>
          <button class="btn" type="submit" :disabled="submitting">{{ submitting ? 'Creating account…' : 'Create account →' }}</button>
        </form>
        <p class="auth-form__row" style="justify-content:center">Already a member? <NuxtLink to="/login">Sign in</NuxtLink></p>
      </div>
    </section>
  </main>
</template>
