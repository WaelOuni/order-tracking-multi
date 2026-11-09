import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, sep, extname } from 'node:path';

// Serveur de tests uniquement. En exécution réelle, Spring WebFlux sert ces fichiers.
const root = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url));
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml' };
const server = createServer(async (req, res) => {
  try {
    const pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
    const path = resolve(root, pathname === '/' ? 'index.html' : pathname.slice(1));
    if (!path.startsWith(root.endsWith(sep) ? root : `${root}${sep}`)) {
      res.writeHead(403).end();
      return;
    }
    const body = await readFile(path);
    res.writeHead(200, {
      'Content-Type': types[extname(path)] || 'application/octet-stream',
      'Cache-Control': 'no-store',
      // Les tests isolés exercent aussi la compatibilité avec une CSP sans inline/CDN.
      'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
    });
    res.end(body);
  } catch {
    res.writeHead(404).end('Not found');
  }
});
server.listen(4173, '127.0.0.1', () => process.stdout.write('UI test server: http://127.0.0.1:4173\n'));
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close(() => process.exit(0)));
