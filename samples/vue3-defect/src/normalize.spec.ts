import { describe, expect, it } from 'vitest'
import { normalizeCount } from './normalize'

describe('normalizeCount', () => {
  it('preserves zero while normalizing an optional count', () => {
    expect(normalizeCount(0)).toBe(0)
    expect(normalizeCount(undefined)).toBeNull()
  })
})
