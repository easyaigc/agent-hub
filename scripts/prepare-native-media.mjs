import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { deflateSync } from 'node:zlib';

export async function prepareNativeMedia(device, directory, nonce) {
  const container = execFileSync(
    'xcrun',
    ['simctl', 'get_app_container', device, 'com.codexmanage.ios', 'data'],
    { encoding: 'utf8' },
  ).trim();
  await mkdir(join(container, 'Documents'), { recursive: true });
  await writeFile(join(container, 'Documents/native-input.txt'), '手机上传内容校验码：' + nonce);
  const crc = (bytes) => {
    let value = 0xffffffff;
    for (const byte of bytes) {
      value ^= byte;
      for (let i = 0; i < 8; i++) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
    }
    return (value ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, bytes) => {
    const name = Buffer.from(type),
      size = Buffer.alloc(4),
      checksum = Buffer.alloc(4);
    size.writeUInt32BE(bytes.length);
    checksum.writeUInt32BE(crc(Buffer.concat([name, bytes])));
    return Buffer.concat([size, name, bytes, checksum]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(64);
  header.writeUInt32BE(64, 4);
  header[8] = 8;
  header[9] = 2;
  const pixels = Buffer.alloc(64 * 193);
  for (let y = 0; y < 64; y++) for (let x = 0; x < 64; x++) pixels[y * 193 + 1 + x * 3] = 255;
  const path = join(directory, 'phone-red.png');
  await writeFile(
    path,
    Buffer.concat([
      Buffer.from('89504e470d0a1a0a', 'hex'),
      chunk('IHDR', header),
      chunk('IDAT', deflateSync(pixels)),
      chunk('IEND', Buffer.alloc(0)),
    ]),
  );
  execFileSync('xcrun', ['simctl', 'addmedia', device, path]);
}
