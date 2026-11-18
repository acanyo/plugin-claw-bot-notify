import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'

function run(args, header = {}, stdin = '', entry = 'build/dist/cli.mjs') {
  return new Promise((resolve, reject) => {
    const p = spawn(process.execPath, [entry, ...args], { env: { PATH: process.env.PATH, NO_COLOR: '1' } })
    let stdout = '', stderr = ''
    p.stdout.on('data', value => stdout += value)
    p.stderr.on('data', value => stderr += value)
    p.on('error', reject)
    p.on('close', code => resolve({ code, stdout, stderr }))
    p.stdin.end(JSON.stringify(header) + '\n' + stdin)
  })
}
const context = { profile: 'clawbot', baseUrl: 'http://127.0.0.1:9', token: 'test-only-private-token' }

test('official help works before binding and retains every command group', async () => {
  const result = await run(['--help'])
  assert.equal(result.code, 0)
  for (const name of ['post', 'comment', 'shop', 'theme', 'backup', 'attachment', 'notification', 'single-page'])
    assert.match(result.stdout, new RegExp(name))
})

test('managed profile diagnostics never disclose credentials', async () => {
  for (const args of [['auth', 'current', '--json'], ['auth', 'profile', 'doctor', '--json']]) {
    const result = await run(args, context)
    assert.equal(result.code, 0, result.stderr)
    assert.doesNotMatch(result.stdout + result.stderr, /test-only-private-token/)
    assert.match(result.stdout, /clawbot/)
  }
})

test('profile mutation fails clearly and other profiles cannot be selected', async () => {
  const mutation = await run(['auth', 'profile', 'use', 'other', '--json'], context)
  assert.equal(mutation.code, 1)
  assert.match(mutation.stderr, /CLI_AUTH_MANAGED/)
  const mismatch = await run(['post', 'list', '--profile=other', '--json'], context)
  assert.equal(mismatch.code, 1)
  assert.match(mismatch.stderr, /CLI_PROFILE_MISMATCH/)
})

test('each invocation receives its own token and no keyring is required', async () => {
  const headers = []
  const server = createServer((req, res) => {
    headers.push(req.headers.authorization)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ items: [], total: 0, page: 1, size: 1 }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    const baseUrl = `http://127.0.0.1:${server.address().port}`
    const results = await Promise.all(['first-token', 'second-token'].map(token =>
      run(['post', 'list', '--page=1', '--size=1', '--json'], { ...context, baseUrl, token })))
    assert.deepEqual(results.map(r => r.code), [0, 0], results.map(r => r.stderr).join('\n'))
    assert.deepEqual(headers.sort(), ['Bearer first-token', 'Bearer second-token'])
    assert.ok(results.every(r => !r.stdout.includes('token')))
  } finally { server.close() }
})

test('credential header leaves original command stdin untouched', async () => {
  const p = spawn(process.execPath, ['--input-type=module', '-e',
    `import './adapter.mjs'; import {readFileSync} from 'node:fs'; process.stdout.write(readFileSync(0,'utf8'));`])
  let output = ''
  p.stdout.on('data', b => output += b)
  const finished = new Promise(resolve => p.on('close', resolve))
  const body = '# 中文正文\n{"a": 1}\nEOF\n'
  p.stdin.end(JSON.stringify(context) + '\n' + body)
  assert.equal(await finished, 0)
  assert.equal(output, body)
})
