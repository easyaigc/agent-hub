import type { IncomingMessage } from 'node:http';
import { DesktopResponses, ResponsesProxyError } from './desktop-responses.js';

type Row = Record<string, unknown>;
const row = (value: unknown): Row =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Row) : {};
const invalid = (param: string, message = '图像参数无效或当前通道尚不支持') =>
  new ResponsesProxyError(400, 'invalid_image_' + param, message);

export const desktopImageModels = ['gpt-image-2'] as const;
export type DesktopImageOperation = 'generations' | 'edits';
export interface DesktopImagesTransport {
  generate(
    body: Buffer,
    signal: AbortSignal,
    beforeSubmit?: () => Promise<void>,
  ): Promise<IncomingMessage>;
  edit(
    body: Buffer,
    signal: AbortSignal,
    beforeSubmit?: () => Promise<void>,
  ): Promise<IncomingMessage>;
  close(): void;
}

/** Fixed named operations share the existing read-only desktop authorization provider. */
export class DesktopImages extends DesktopResponses implements DesktopImagesTransport {
  generate(body: Buffer, signal: AbortSignal, beforeSubmit?: () => Promise<void>) {
    return this.sendNamed('images/generations', body, signal, beforeSubmit);
  }
  edit(body: Buffer, signal: AbortSignal, beforeSubmit?: () => Promise<void>) {
    return this.sendNamed('images/edits', body, signal, beforeSubmit);
  }
}

/** Header-only inspection: no decompression, local path reads, URL fetches or image resizing. */
export function inspectImage(bytes: Buffer) {
  let format: 'png' | 'jpeg' | 'webp',
    width = 0,
    height = 0;
  if (
    bytes.length >= 33 &&
    bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])) &&
    bytes.toString('ascii', 12, 16) === 'IHDR'
  ) {
    format = 'png';
    width = bytes.readUInt32BE(16);
    height = bytes.readUInt32BE(20);
  } else if (bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216) {
    format = 'jpeg';
    let offset = 2;
    while (offset + 4 <= bytes.length) {
      if (bytes[offset++] !== 255) break;
      while (bytes[offset] === 255) offset++;
      const marker = bytes[offset++];
      if (marker === 217 || marker === 218) break;
      if (marker === 1 || (marker !== undefined && marker >= 208 && marker <= 215)) continue;
      if (offset + 2 > bytes.length) break;
      const size = bytes.readUInt16BE(offset);
      if (size < 2 || offset + size > bytes.length) break;
      if (
        marker !== undefined &&
        [192, 193, 194, 195, 197, 198, 199, 201, 202, 203, 205, 206, 207].includes(marker) &&
        size >= 8
      ) {
        height = bytes.readUInt16BE(offset + 3);
        width = bytes.readUInt16BE(offset + 5);
        break;
      }
      offset += size;
    }
  } else if (
    bytes.length >= 30 &&
    bytes.toString('ascii', 0, 4) === 'RIFF' &&
    bytes.toString('ascii', 8, 12) === 'WEBP'
  ) {
    format = 'webp';
    const chunk = bytes.toString('ascii', 12, 16);
    if (chunk === 'VP8X') {
      width = bytes.readUIntLE(24, 3) + 1;
      height = bytes.readUIntLE(27, 3) + 1;
    } else if (chunk === 'VP8 ' && bytes.subarray(23, 26).equals(Buffer.from([157, 1, 42]))) {
      width = bytes.readUInt16LE(26) & 16383;
      height = bytes.readUInt16LE(28) & 16383;
    } else if (chunk === 'VP8L' && bytes[20] === 47) {
      const bits = bytes.readUInt32LE(21);
      width = (bits & 16383) + 1;
      height = ((bits >>> 14) & 16383) + 1;
    }
  } else throw invalid('image', '只支持有效 PNG、JPEG 或 WebP 图片');
  if (!width || !height || width > 65535 || height > 65535 || width * height > 100_000_000)
    throw invalid('image', '图片尺寸无效或超过像素上限');
  return { format, width, height };
}

function decodeImage(encoded: string) {
  if (!encoded || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(encoded))
    throw invalid('image');
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length > 20 * 1024 * 1024 || bytes.toString('base64') !== encoded)
    throw invalid('image');
  return { bytes, ...inspectImage(bytes) };
}

function reference(value: unknown, mask = false) {
  const image = row(value);
  if (
    Object.keys(image).length !== 1 ||
    Object.keys(image).some((key) => !['image_url', 'file_id'].includes(key)) ||
    (typeof image.image_url === 'string') === (typeof image.file_id === 'string')
  )
    throw invalid(mask ? 'mask' : 'images');
  if (typeof image.file_id === 'string') {
    if (!/^file-[A-Za-z0-9_-]{1,240}$/.test(image.file_id)) throw invalid('file_id');
    return { file_id: image.file_id };
  }
  const url = image.image_url as string;
  if (url.startsWith('data:')) {
    const match = /^data:image\/(png|jpeg|webp);base64,(.+)$/.exec(url);
    if (!match) throw invalid('image');
    const decoded = decodeImage(match[2]!);
    if (decoded.format !== match[1] || (mask && decoded.format !== 'png'))
      throw invalid(mask ? 'mask' : 'image');
  } else {
    let parsed: URL;
    try {
      parsed = new URL(url);
    } catch {
      throw invalid('image_url');
    }
    if (url.length > 8192 || parsed.protocol !== 'https:' || parsed.username || parsed.password)
      throw invalid('image_url');
  }
  return { image_url: url };
}

export function prepareDesktopImage(
  value: unknown,
  operation: DesktopImageOperation,
  models: readonly string[],
) {
  const data = { ...row(value) };
  const fields = [
    'model',
    'prompt',
    'n',
    'size',
    'quality',
    'background',
    'output_format',
    'output_compression',
    'moderation',
    'safety_identifier',
    'user',
    'response_format',
    'stream',
    'partial_images',
  ];
  if (operation === 'edits') fields.push('images', 'mask', 'input_fidelity');
  if (Object.keys(data).some((key) => !fields.includes(key)))
    throw invalid('request', '请求包含当前图像通道不支持的参数');
  // OpenAI optional fields accept null as omitted; required fields still fail below.
  for (const key of fields) if (data[key] === null) delete data[key];
  const model = data.model ?? models[0];
  if (
    typeof model !== 'string' ||
    !models.includes(model) ||
    !desktopImageModels.some((id) => id === model)
  )
    throw invalid('model', '请选择已启用且验证过的图像模型');
  if (typeof data.prompt !== 'string' || !data.prompt.trim() || data.prompt.length > 32000)
    throw invalid('prompt');
  if (data.n !== undefined && data.n !== 1)
    throw invalid('n', '当前通道每个操作只支持 n=1，不自动拆分或重放');
  if (data.response_format !== undefined && data.response_format !== 'b64_json')
    throw invalid('response_format', '当前通道只返回 b64_json');
  if (data.stream !== undefined && typeof data.stream !== 'boolean') throw invalid('stream');
  if (data.partial_images !== undefined && data.partial_images !== 0)
    throw invalid('partial_images', '当前流式返回只发送最终图片，partial_images 只支持 0');
  const preferences: string[] = [],
    preferenceFields: string[] = [];
  const choose = (key: string, values: readonly string[], describe: (value: string) => string) => {
    const v = data[key];
    if (v === undefined) return;
    if (typeof v !== 'string' || !values.includes(v)) throw invalid(key);
    if (v !== 'auto') {
      preferences.push(describe(v));
      preferenceFields.push(key);
    }
  };
  if (data.size !== undefined && data.size !== 'auto') {
    if (typeof data.size !== 'string') throw invalid('size');
    const match = /^(\d{3,4})x(\d{3,4})$/.exec(data.size);
    if (!match) throw invalid('size');
    const width = Number(match[1]),
      height = Number(match[2]),
      pixels = width * height;
    if (
      width % 16 ||
      height % 16 ||
      Math.max(width, height) > 3840 ||
      Math.max(width, height) / Math.min(width, height) > 3 ||
      pixels < 655360 ||
      pixels > 8294400
    )
      throw invalid('size');
    const gcd = (a: number, b: number): number => (b ? gcd(b, a % b) : a);
    const factor = gcd(width, height);
    preferences.push(
      `Use the entire output image canvas at ${width / factor}:${height / factor} width-to-height aspect ratio, preferably ${width} pixels wide by ${height} pixels tall. Fill the canvas without letterboxing, padding, or a smaller image inside another frame. Do not draw dimension labels or these instructions in the image.`,
    );
    preferenceFields.push('size');
  }
  choose(
    'quality',
    ['auto', 'low', 'medium', 'high'],
    (v) =>
      `Preferred image quality is ${v}; ${v === 'high' ? 'preserve fine detail, clear edges and legible requested text' : v === 'low' ? 'favor a simple efficient rendering' : 'balance detail and rendering effort'}.`,
  );
  choose('background', ['auto', 'opaque', 'transparent'], (v) =>
    v === 'transparent'
      ? 'Prefer a real transparent alpha background; do not draw a checkerboard or a solid-color imitation.'
      : 'Use an opaque background.',
  );
  choose(
    'output_format',
    ['png', 'jpeg', 'webp'],
    (v) => `Preferred output image encoding is ${v}.`,
  );
  choose(
    'input_fidelity',
    ['low', 'high'],
    (v) =>
      `When editing, preserve the source image identity, geometry and unaffected details with ${v} input fidelity.`,
  );
  if (data.output_compression !== undefined) {
    if (
      !Number.isInteger(data.output_compression) ||
      Number(data.output_compression) < 0 ||
      Number(data.output_compression) > 100 ||
      !['jpeg', 'webp'].includes(String(data.output_format))
    )
      throw invalid('output_compression');
    preferences.push(
      `Preferred output compression quality is ${data.output_compression} out of 100.`,
    );
    preferenceFields.push('output_compression');
  }
  if (
    data.moderation !== undefined &&
    (typeof data.moderation !== 'string' || !['auto', 'low'].includes(data.moderation))
  )
    throw invalid('moderation');
  for (const key of ['user', 'safety_identifier'])
    if (
      data[key] !== undefined &&
      (typeof data[key] !== 'string' || (data[key] as string).length > 128)
    )
      throw invalid(key);
  const body: Row = { model, prompt: data.prompt, n: 1, stream: false };
  if (preferences.length)
    body.prompt =
      data.prompt +
      '\n\nImage output preferences (best effort; actual output parameters may differ):\n' +
      preferences.join('\n');
  if ((body.prompt as string).length > 32000) throw invalid('prompt', '提示词加输出偏好后超过上限');
  for (const key of ['moderation', 'safety_identifier', 'user'])
    if (data[key] !== undefined) body[key] = data[key];
  if (operation === 'edits') {
    if (!Array.isArray(data.images) || data.images.length < 1 || data.images.length > 16)
      throw invalid('images');
    body.images = data.images.map((image) => reference(image));
    if (data.mask !== undefined) body.mask = reference(data.mask, true);
  }
  return {
    model,
    body: Buffer.from(JSON.stringify(body)),
    stream: data.stream === true,
    preferenceFields,
  };
}

/** Never substitute requested values for actual output metadata. */
export function validateDesktopImageResponse(value: unknown) {
  const data = row(value);
  if (
    !Number.isSafeInteger(data.created) ||
    Number(data.created) < 0 ||
    !Array.isArray(data.data) ||
    data.data.length !== 1
  )
    throw new ResponsesProxyError(502, 'invalid_image_response', '上游未返回完整的单张图片结果');
  const item = row(data.data[0]);
  if (typeof item.b64_json !== 'string')
    throw new ResponsesProxyError(502, 'invalid_image_response', '上游未返回可核对的 Base64 图片');
  let decoded: ReturnType<typeof decodeImage>;
  try {
    decoded = decodeImage(item.b64_json);
  } catch {
    throw new ResponsesProxyError(502, 'invalid_image_response', '上游图片格式无效或超过上限');
  }
  const size = `${decoded.width}x${decoded.height}`;
  if (
    (data.size !== undefined && data.size !== size) ||
    (data.output_format !== undefined && data.output_format !== decoded.format)
  )
    throw new ResponsesProxyError(502, 'invalid_image_response', '上游图片元数据与实际文件不一致');
  const response: Row = {
    created: data.created,
    data: [
      {
        b64_json: item.b64_json,
        ...(typeof item.revised_prompt === 'string' ? { revised_prompt: item.revised_prompt } : {}),
      },
    ],
    size,
    output_format: decoded.format,
  };
  if (['low', 'medium', 'high'].includes(String(data.quality))) response.quality = data.quality;
  if (['opaque', 'transparent'].includes(String(data.background)))
    response.background = data.background;
  if (data.usage !== undefined) response.usage = data.usage;
  return response;
}
