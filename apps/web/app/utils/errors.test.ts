import { describe, expect, it } from 'vitest'
import { getRequestError } from './errors'

describe('getRequestError', () => {
  it('prefers a safe API message', () => {
    expect(getRequestError({ data: { message: 'Try again.' } }, 'Fallback')).toBe('Try again.')
  })
  it('uses the fallback for oversized or missing messages', () => {
    expect(getRequestError({ message: 'x'.repeat(241) }, 'Fallback')).toBe('Fallback')
    expect(getRequestError(null, 'Fallback')).toBe('Fallback')
  })
})
