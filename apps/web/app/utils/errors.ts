export function getRequestError(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const data = 'data' in error ? (error as { data?: { message?: unknown } }).data : undefined
    if (data && typeof data.message === 'string') return data.message
    const message = 'message' in error ? (error as { message?: unknown }).message : undefined
    if (typeof message === 'string' && message.length < 240) return message
  }
  return fallback
}
