import { createHash } from 'node:crypto';
import { link, lstat, mkdir, open } from 'node:fs/promises';
import { dirname } from 'node:path';
import { constants } from 'node:fs';
import { protectWindowsStateDirectory, protectWindowsStateFile } from './desktop-platform.js';

/** Both paths are private Host state on the same volume; publication never replaces a file. */
export async function publishUploadedFile(
  source: string,
  destination: string,
  size: number,
  sha256: string,
) {
  await mkdir(dirname(destination), { recursive: true, mode: 0o700 });
  if (!(await lstat(dirname(destination))).isDirectory())
    throw new Error('invalid-upload-directory');
  protectWindowsStateDirectory(dirname(destination));
  try {
    await link(source, destination);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
  }
  const published = await lstat(destination, { bigint: true });
  if (!published.isFile() || published.isSymbolicLink())
    throw new Error('upload-publication-conflict');
  const file = await open(destination, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const before = await file.stat({ bigint: true });
    if (
      !before.isFile() ||
      before.size !== BigInt(size) ||
      before.dev !== published.dev ||
      before.ino !== published.ino
    )
      throw new Error('upload-publication-conflict');
    const hash = createHash('sha256');
    for await (const chunk of file.createReadStream({ autoClose: false })) hash.update(chunk);
    const after = await file.stat({ bigint: true });
    const current = await lstat(destination, { bigint: true });
    if (
      hash.digest('hex') !== sha256 ||
      before.mtimeNs !== after.mtimeNs ||
      before.ctimeNs !== after.ctimeNs ||
      !current.isFile() ||
      current.isSymbolicLink() ||
      current.dev !== after.dev ||
      current.ino !== after.ino
    )
      throw new Error('upload-publication-conflict');
    await file.sync();
  } finally {
    await file.close();
  }
  protectWindowsStateFile(destination);
  if (process.platform !== 'win32') {
    const directory = await open(dirname(destination), 'r');
    try {
      await directory.sync();
    } finally {
      await directory.close();
    }
  }
}
