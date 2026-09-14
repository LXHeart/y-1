import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFile } from 'node:fs/promises'

/** Isolated UI host: no .env, proxy, database, model credentials, or external API fallback. */
export async function startStudioServers(basePort = 28080) {
  const servers = []
  for (const [offset, entry] of ['index.html', 'ops.html', 'ai.html'].entries()) {
    const server = await createServer({
      configFile: false, envDir: false, root: process.cwd(),
      plugins: [vue(), {
        name: 'studio-fixture-host',
        configureServer(server) {
          server.middlewares.use((req, res, next) => {
            if (req.url === '/__studio-fixture-health') {
              res.setHeader('Content-Type', 'application/json')
              res.end(JSON.stringify({ kind: 'creation-studio-fixture', networkProxy: false })); return
            }
            const fileId = req.url?.match(/^\/fixture-files\/([0-9a-f-]{36})$/)?.[1]
            if (fileId) {
              void readFile('test-artifacts/task-101/http-files/' + fileId).then(bytes => {
                res.setHeader('Content-Type', 'application/zip')
                res.setHeader('Content-Disposition', 'attachment; filename="studio-fixture.zip"')
                res.end(bytes)
              }).catch(() => { res.statusCode = 404; res.end() })
              return
            }
            if (req.url?.startsWith('/api/')) {
              res.statusCode = 501; res.setHeader('Content-Type', 'application/json')
              res.end(JSON.stringify({ success: false, error: 'UI fixture did not handle this API' })); return
            }
            if (req.method === 'GET' && req.headers.accept?.includes('text/html')) req.url = '/' + entry
            next()
          })
        },
      }],
      server: { host: '127.0.0.1', port: basePort + offset, strictPort: true },
    })
    await server.listen(); servers.push(server)
  }
  return async () => { await Promise.all(servers.map(server => server.close())) }
}

if (process.argv[1]?.endsWith('creation-studio-server.mjs')) {
  const close = await startStudioServers(Number(process.env.STUDIO_UI_PORT || 28080))
  console.log('Isolated studio UI hosts ready: 28080 / 28081 / 28082')
  for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await close(); process.exit(0) })
}
