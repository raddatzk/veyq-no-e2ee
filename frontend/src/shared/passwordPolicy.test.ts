import { describe, it, expect } from 'vitest'
import { passwordTooShort, passwordHintKey, MIN_PASSWORD_LENGTH } from './passwordPolicy'

describe('passwordPolicy', () => {
  it('minimum length is 10', () => expect(MIN_PASSWORD_LENGTH).toBe(10))

  it('tooShort only flags a non-empty password below the minimum', () => {
    expect(passwordTooShort('')).toBe(false)
    expect(passwordTooShort('short')).toBe(true)
    expect(passwordTooShort('1234567890')).toBe(false)
  })

  it('hint key follows the length tiers', () => {
    expect(passwordHintKey('')).toBe(null)
    expect(passwordHintKey('abc')).toBe('password.minLength')
    expect(passwordHintKey('1234567890')).toBe(null) // 10 chars — acceptable middle, no nag
    expect(passwordHintKey('abcdefghijklmnop')).toBe('password.strong') // 16 chars
  })
})
