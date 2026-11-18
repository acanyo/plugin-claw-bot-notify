import { build } from 'esbuild'
import { readFile, writeFile, mkdir, readdir, copyFile } from 'node:fs/promises'
import { resolve, dirname, join } from 'node:path'

const root = resolve('node_modules/@halo-dev/cli')
const original = await readFile(join(root, 'dist/cli.mjs'), 'utf8')
const needle = 'constructor(configStore = new ConfigStore())'
if (original.split(needle).length !== 2) throw new Error('Upstream CLI credential contract changed')
const source = `import { ManagedConfigStore } from ${JSON.stringify(resolve('adapter.mjs'))};\n`
  + original.replace(/^#![^\n]*\n/, '').replace(needle, 'constructor(configStore = new ManagedConfigStore())')
await mkdir('build/dist', { recursive: true })
await build({
  stdin: { contents: source, resolveDir: root, sourcefile: 'halo-managed.mjs', loader: 'js' },
  bundle: true, platform: 'node', target: 'node22', format: 'esm',
  outfile: 'build/dist/cli.mjs', minify: true,
  alias: { '@napi-rs/keyring': resolve('keyring.mjs') },
  banner: { js: 'import { createRequire as __clawbotRequire } from "node:module";const require=__clawbotRequire(import.meta.url);' },
  legalComments: 'eof',
})
await copyFile(join(root, 'LICENSE'), 'build/dist/HALO-CLI-LICENSE')
await copyFile('NODE-LICENSE', 'build/dist/NODE-LICENSE')
await writeFile('build/dist/version.txt', '1.4.0\n')
// Carry licenses for every dependency in the single-file distribution.
const licenses = []
async function scan(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name === '.bin') continue
    const pkg = join(dir, entry.name)
    if (entry.name.startsWith('@')) { await scan(pkg); continue }
    let info
    try { info = JSON.parse(await readFile(join(pkg, 'package.json'), 'utf8')) } catch { continue }
    if (info.name === 'esbuild' || info.name?.startsWith('@esbuild/') || info.name?.startsWith('@napi-rs/')) continue
    for (const file of await readdir(pkg)) if (/^(license|licence|copying)(\.|$)/i.test(file)) {
      licenses.push(`${info.name}@${info.version}\n${await readFile(join(pkg, file), 'utf8')}\n`)
    }
    try { await scan(join(pkg, 'node_modules')) } catch (e) { if (e.code !== 'ENOENT') throw e }
  }
}
await scan(resolve('node_modules'))
await writeFile('build/dist/THIRD-PARTY-NOTICES.txt', licenses.join('\n---\n'))

// Installation metadata only: the actual CLI and its dependencies are fetched on
// the server after the administrator starts the setup wizard.
const lock = JSON.parse(await readFile('package-lock.json', 'utf8'))
const packages = Object.entries(lock.packages)
  .filter(([path, pkg]) => path && !pkg.dev && !pkg.optional)
  .map(([path, pkg]) => ({ path, url: pkg.resolved, integrity: pkg.integrity }))
const { createHash } = await import('node:crypto')
const files = {}
for (const pkg of packages) {
  if (!pkg.url?.startsWith('https://registry.npmjs.org/') || !pkg.integrity?.startsWith('sha512-'))
    throw new Error('Runtime dependencies must use pinned official npm artifacts')
  async function hashes(dir, relative) {
    for (const entry of await readdir(dir, { withFileTypes: true })) {
      if (entry.name === 'node_modules' || entry.name === '.bin') continue
      const path = join(dir, entry.name), name = relative + '/' + entry.name
      if (entry.isDirectory()) await hashes(path, name)
      else if (entry.isFile()) files[name] = createHash('sha256').update(await readFile(path)).digest('hex')
      else throw new Error('Unexpected runtime dependency symlink: ' + name)
    }
  }
  await hashes(resolve(pkg.path), pkg.path)
}
await mkdir('build/installer', { recursive: true })
for (const name of ['adapter.mjs', 'keyring.mjs', 'runtimes.json', 'NODE-LICENSE'])
  await copyFile(name, 'build/installer/' + name)
for (const name of ['HALO-CLI-LICENSE', 'THIRD-PARTY-NOTICES.txt', 'version.txt'])
  await copyFile('build/dist/' + name, 'build/installer/' + name)
await writeFile('build/installer/distribution.json', JSON.stringify({ version: '1.4.0', packages, files }))
