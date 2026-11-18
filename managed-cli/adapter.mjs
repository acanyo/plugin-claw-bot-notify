import { readSync } from 'node:fs'

// Read exactly one header line. The remaining bytes belong to the command's
// original stdin (Markdown, JSON, etc.). Never put the token in argv, env or disk.
const bytes = []
const one = Buffer.alloc(1)
while (true) {
  if (bytes.length >= 65536 || readSync(0, one, 0, 1) !== 1)
    throw new Error('CLI_AUTH_CONTEXT_INVALID')
  if (one[0] === 10) break
  bytes.push(one[0])
}
let context
try { context = JSON.parse(Buffer.from(bytes).toString('utf8')) }
catch { throw new Error('CLI_AUTH_CONTEXT_INVALID') }

export class ManagedConfigStore {
  profile(name) {
    if (!context.token || !context.baseUrl || !context.profile)
      throw new Error('CLI_BINDING_REQUIRED')
    if (name && name !== context.profile) throw new Error('CLI_PROFILE_MISMATCH')
    const url = new URL(context.baseUrl)
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.search || url.hash) throw new Error('CLI_SITE_MISMATCH')
    return {
      name: context.profile, baseUrl: context.baseUrl,
      auth: { type: 'bearer' },
      createdAt: '1970-01-01T00:00:00Z', updatedAt: '1970-01-01T00:00:00Z',
    }
  }
  async getActiveStoredProfile(name) { return this.profile(name) }
  async getStoredProfile(name) { return name === context.profile ? this.profile(name) : undefined }
  async listProfiles() { return { activeProfile: context.profile, profiles: [this.profile()] } }
  async getActiveResolvedProfile(name) {
    return { ...this.profile(name), auth: { type: 'bearer', token: context.token } }
  }
  async getResolvedProfile(name) { return this.getActiveResolvedProfile(name) }
  async inspectProfileCredentials() {
    const p = this.profile()
    return { activeProfile: p.name, ok: true, profiles: [{
      name: p.name, baseUrl: p.baseUrl, authType: 'bearer', status: 'ok',
    }] }
  }
  async setActiveProfile() { throw new Error('CLI_AUTH_MANAGED') }
  async deleteProfile() { throw new Error('CLI_AUTH_MANAGED') }
  async upsertProfile() { throw new Error('CLI_AUTH_MANAGED') }
}
