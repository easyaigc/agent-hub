/** Host presentation cursors are never opaque Codex RPC cursors. */
export const desktopHistoryCursorPrefix = 'desktop-page-v1:';

export function isDesktopHistoryCursor(value: unknown): value is string {
  return typeof value === 'string' && value.startsWith(desktopHistoryCursorPrefix);
}

export function parseDesktopHistoryCursor(
  value: string,
): readonly [string, string, string] | undefined {
  if (!isDesktopHistoryCursor(value) || value.length > 8192) return undefined;
  const encoded = value.slice(desktopHistoryCursorPrefix.length);
  if (!/^[A-Za-z0-9_-]+$/.test(encoded)) return undefined;
  try {
    const bytes = Buffer.from(encoded, 'base64url');
    const text = bytes.toString('utf8');
    if (Buffer.from(text, 'utf8').toString('base64url') !== encoded) return undefined;
    const decoded: unknown = JSON.parse(text);
    if (
      !Array.isArray(decoded) ||
      decoded.length !== 3 ||
      !decoded.every((part) => typeof part === 'string' && part.length > 0 && part.length <= 4096)
    )
      return undefined;
    return decoded as [string, string, string];
  } catch {
    return undefined;
  }
}

export function desktopHistoryCursor(hostId: string, threadId: string, anchor: string): string {
  return (
    desktopHistoryCursorPrefix +
    Buffer.from(JSON.stringify([hostId, threadId, anchor])).toString('base64url')
  );
}
