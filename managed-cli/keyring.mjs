// The managed ConfigStore never opens a system keyring. Fail closed if upstream
// unexpectedly introduces another keyring caller.
export class Entry {
  constructor() { throw new Error('CLI_AUTH_MANAGED') }
}
