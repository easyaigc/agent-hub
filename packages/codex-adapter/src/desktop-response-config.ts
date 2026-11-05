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
  if (value.model_provider !== undefined && value.model_provider !== 'openai')
    throw new Error('Non-OpenAI provider is unsupported');
  for (const profile of Object.values((value.profiles ?? {}) as Record<string, unknown>)) {
    if (!profile || typeof profile !== 'object' || Array.isArray(profile))
      throw new Error('Invalid Codex profile');
    if ('openai_base_url' in profile || 'model_provider' in profile || 'base_url' in profile)
      throw new Error('Profile provider override is unsupported');
  }
  const newline = body.includes('\r\n') ? '\r\n' : '\n';
  const prefix = `openai_base_url = "${url}"${newline}`;
  const own = value.openai_base_url === url && body.startsWith(prefix);
  if (value.openai_base_url !== undefined && !own) throw new Error('Conflicting openai_base_url');
  return { hasBom, body, newline, prefix, own };
}
