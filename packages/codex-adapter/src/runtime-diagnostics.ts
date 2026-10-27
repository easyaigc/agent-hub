/** Only fixed labels and OS error identifiers may leave a failing persistence path. */
export function runtimeFailure(scope: string, error: unknown, attempt?: number): void {
  const value = error as { code?: unknown; syscall?: unknown; stage?: unknown; cause?: unknown };
  const cause = value?.cause as
    { code?: unknown; syscall?: unknown; status?: unknown; stderr?: unknown } | undefined;
  const identifier = (input: unknown) =>
    typeof input === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(input) ? input : undefined;
  console.error(
    JSON.stringify({
      diagnostic: 'host-runtime-failure',
      scope: identifier(scope),
      stage: identifier(value?.stage),
      code: identifier(value?.code ?? cause?.code),
      syscall: identifier(value?.syscall ?? cause?.syscall),
      nativeStatus: Number.isSafeInteger(cause?.status) ? cause?.status : undefined,
      nativeType: String(cause?.stderr ?? '').match(
        /^UNSAFE_WINDOWS_PRIVATE_STATE:(UnauthorizedAccessException|IOException|SecurityException|Win32Exception|ArgumentException)\s*$/m,
      )?.[1],
      ...(attempt === undefined ? {} : { attempt }),
    }),
  );
}

import { appendFileSync, existsSync, lstatSync, mkdirSync, renameSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import {
  assertPrivateFile,
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from './desktop-platform.js';

const recentDiagnostics = new Map<string, number>();
/** Bounded private diagnostics; never serialize request headers, bodies or raw error objects. */
export function appendRuntimeDiagnostic(
  directory: string,
  context: {
    scope: string;
    stage?: string | undefined;
    hostId: string;
    threadId?: string | undefined;
    messageId?: string | undefined;
    resourceId?: string | undefined;
    path?: string | undefined;
    requestId?: string | undefined;
    route?: string | undefined;
    method?: string | undefined;
    status?: number | undefined;
    elapsedMs?: number | undefined;
    attempt?: number | undefined;
  },
  error: unknown,
): void {
  const value = error as {
    code?: unknown;
    name?: unknown;
    status?: unknown;
    syscall?: unknown;
    stack?: unknown;
    cause?: { code?: unknown; syscall?: unknown };
    fileChange?: unknown;
  };
  const identifier = (value: unknown) =>
    typeof value === 'string' && /^[A-Za-z0-9_.:-]{1,200}$/.test(value) ? value : undefined;
  // Only our fixed stat fields are persisted, never an arbitrary error payload.
  const change = value?.fileChange as Record<string, unknown> | undefined;
  const stamp = (input: unknown) => {
    if (!input || typeof input !== 'object') return undefined;
    const source = input as Record<string, unknown>;
    return Object.fromEntries(
      ['dev', 'ino', 'size', 'mtimeNs', 'ctimeNs', 'birthtimeNs'].map((key) => [
        key,
        typeof source[key] === 'string' && /^-?\d{1,40}$/.test(source[key])
          ? source[key]
          : undefined,
      ]),
    );
  };
  const row = {
    at: new Date().toISOString(),
    ...context,
    code: identifier(value?.code ?? value?.cause?.code),
    errorName: identifier(value?.name),
    syscall: identifier(value?.syscall ?? value?.cause?.syscall),
    nativeCode: Number.isSafeInteger(value?.code) ? value.code : undefined,
    status: context.status ?? (Number.isInteger(value?.status) ? value.status : undefined),
    fileChange:
      change && ['metadata-only', 'content-or-target'].includes(String(change.kind))
        ? {
            stage: identifier(change.stage),
            kind: change.kind,
            before: stamp(change.before),
            after: stamp(change.after),
          }
        : undefined,
    stack:
      typeof value?.stack === 'string'
        ? value.stack
            .split('\n')
            .filter((line) => /^\s+at /.test(line))
            .slice(0, 6)
        : undefined,
  };
  if (context.stage === 'register') {
    const key = JSON.stringify([
      context.hostId,
      context.threadId,
      context.messageId,
      context.path,
      row.code,
    ]);
    const now = Date.now();
    if (now - (recentDiagnostics.get(key) ?? 0) < 30_000) return;
    recentDiagnostics.set(key, now);
    if (recentDiagnostics.size > 512)
      recentDiagnostics.delete(recentDiagnostics.keys().next().value!);
  }
  try {
    const root = join(directory, 'diagnostics');
    mkdirSync(root, { recursive: true, mode: 0o700 });
    protectWindowsStateDirectory(root);
    const file = join(root, 'errors.jsonl');
    if (existsSync(file)) {
      assertPrivateFile(file);
      if (lstatSync(file).size >= 2 * 1024 * 1024) {
        rmSync(file + '.1', { force: true });
        renameSync(file, file + '.1');
      }
    }
    appendFileSync(file, JSON.stringify(row) + '\n', { mode: 0o600 });
    protectWindowsStateFile(file);
  } catch (writeError) {
    runtimeFailure('diagnostic-write', writeError);
  }
}
