import { parse } from 'smol-toml';

/** Parse for conflicts only. Never serialize or log the user's configuration. */
export function inspectDesktopResponseConfigToml(bytes: Buffer, url: string) {
  if (bytes.length > 1024 * 1024) throw new Error('Codex configuration exceeds limit');
  const hasBom = bytes.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]));
  const original = bytes.subarray(hasBom ? 3 : 0),
    body = original.toString('utf8');
  if (!Buffer.from(body).equals(original)) throw new Error('Codex configuration is not UTF-8');
  let value: Record<string, unknown>;
  try {
    value = parse(body, { integersAsBigInt: true });
  } catch {
    throw new Error('Invalid Codex TOML configuration');
  }
  const line = `openai_base_url = "${url}"`;
  const prefix = line + (body.startsWith(line + '\r\n') ? '\r\n' : '\n');
  const own = value.openai_base_url === url;
  if (own && !body.startsWith(prefix)) throw new Error('Conflicting openai_base_url');
  return { hasBom, body, prefix, own };
}
