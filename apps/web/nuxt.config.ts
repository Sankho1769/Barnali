export default defineNuxtConfig({
  compatibilityDate: '2026-10-22',
  devtools: { enabled: false },
  css: [
    '~/assets/css/barnali.css',
    '~/assets/css/barnali-light.css',
    '~/assets/css/app.css'
  ],
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_PUBLIC_API_BASE || 'http://localhost:8080/api/v1'
    }
  },
  app: {
    head: {
      title: 'BARNALI — A community for curious minds',
      htmlAttrs: { lang: 'en' },
      meta: [
        { name: 'theme-color', content: '#F9F5EA' },
        { name: 'description', content: 'Ask questions, share experiences and learn from a community of curious minds.' }
      ],
      link: [{ rel: 'icon', type: 'image/svg+xml', href: '/assets/brand/logos/favicon.svg' }]
    }
  },
  typescript: { strict: true, typeCheck: true }
})
