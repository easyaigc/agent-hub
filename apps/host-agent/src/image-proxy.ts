import {
  ResponsesProxyError,
  inspectImage,
  type DesktopImageOperation,
} from '@codex-manage/codex-adapter';

const invalid = (message: string) => new ResponsesProxyError(400, 'invalid_image_request', message);

/** SDK multipart files become inline image references; filenames never become local paths. */
export async function parseImageRequest(
  body: Buffer,
  contentType: string,
  operation: DesktopImageOperation,
) {
  if (/^application\/json(?:\s*;|$)/i.test(contentType)) {
    try {
      return JSON.parse(body.toString('utf8')) as unknown;
    } catch {
      throw invalid('请求 JSON 无效');
    }
  }
  if (operation !== 'edits' || !/^multipart\/form-data\s*;/i.test(contentType))
    throw new ResponsesProxyError(
      415,
      'image_content_type_required',
      '生成需要 JSON，编辑支持 JSON 或 multipart/form-data',
    );
  let form: FormData;
  try {
    form = await new Request('http://localhost/', {
      method: 'POST',
      headers: { 'content-type': contentType },
      body: new Uint8Array(body),
    }).formData();
  } catch {
    throw invalid('multipart 请求无效');
  }
  const data: Record<string, unknown> = Object.create(null),
    images: { image_url: string }[] = [];
  let count = 0,
    imageField: string | undefined;
  for (const [name, value] of form) {
    if (++count > 40) throw invalid('multipart 字段过多');
    if (typeof value !== 'string') {
      if (
        !['image', 'image[]', 'mask'].includes(name) ||
        value.size > 20 * 1024 * 1024 ||
        !value.size
      )
        throw invalid('图片字段或大小无效');
      const bytes = Buffer.from(await value.arrayBuffer()),
        info = inspectImage(bytes);
      if (
        value.type &&
        value.type !== 'application/octet-stream' &&
        value.type !== 'image/' + info.format
      )
        throw invalid('图片 MIME 与实际文件不一致');
      const reference = {
        image_url: `data:image/${info.format};base64,${bytes.toString('base64')}`,
      };
      if (name === 'mask') {
        if (data.mask || info.format !== 'png') throw invalid('mask 必须是单张 PNG');
        data.mask = reference;
      } else {
        if (imageField && imageField !== name) throw invalid('不能混合 image 与 image[]');
        imageField = name;
        images.push(reference);
        if (images.length > 16) throw invalid('最多支持 16 张参考图');
      }
    } else {
      if (['image', 'image[]', 'mask', 'images'].includes(name) || name in data)
        throw invalid('图片必须使用文件字段，普通字段不能重复');
      if (
        ![
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
          'input_fidelity',
        ].includes(name)
      )
        throw invalid('不支持的 multipart 字段');
      if (['n', 'output_compression', 'partial_images'].includes(name)) {
        if (!/^\d+$/.test(value)) throw invalid('数值字段无效');
        data[name] = Number(value);
      } else if (name === 'stream') {
        if (!['true', 'false'].includes(value)) throw invalid('stream 必须为 true 或 false');
        data[name] = value === 'true';
      } else data[name] = value;
    }
  }
  data.images = images;
  return data;
}
