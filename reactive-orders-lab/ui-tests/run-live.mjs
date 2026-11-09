import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';

// Fonctionne aussi dans PowerShell : aucune syntaxe POSIX VAR=value n'est nécessaire.
const require = createRequire(import.meta.url);
const child = spawn(process.execPath, [require.resolve('@playwright/test/cli'), 'test', '--grep', '@live', ...process.argv.slice(2)], {
  stdio: 'inherit',
  env: { ...process.env, PLAYWRIGHT_LIVE: '1' },
});
child.on('error', (error) => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
child.on('exit', (code) => { process.exitCode = code ?? 1; });
