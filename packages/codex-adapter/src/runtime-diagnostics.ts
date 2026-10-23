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
