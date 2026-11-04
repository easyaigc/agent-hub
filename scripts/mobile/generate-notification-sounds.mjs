import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Same PCM synthesis as iOS TaskNotificationSoundLibrary.prepare; no recorded audio.
const directory = new URL('../../apps/android/src/main/res/raw/', import.meta.url);
const tones = { soft: [660], chime: [660, 880], attention: [880, 660, 880] };
const check = process.argv.includes('--check');
if (!check) await mkdir(directory, { recursive: true });
for (const [name, frequencies] of Object.entries(tones)) {
  const rate = 22050;
  const frames = Math.trunc(rate * frequencies.length * 0.24);
  const result = Buffer.alloc(44 + frames * 2);
  result.write('RIFF', 0);
  result.writeUInt32LE(result.length - 8, 4);
  result.write('WAVEfmt ', 8);
  result.writeUInt32LE(16, 16);
  result.writeUInt16LE(1, 20);
  result.writeUInt16LE(1, 22);
  result.writeUInt32LE(rate, 24);
  result.writeUInt32LE(rate * 2, 28);
  result.writeUInt16LE(2, 32);
  result.writeUInt16LE(16, 34);
  result.write('data', 36);
  result.writeUInt32LE(frames * 2, 40);
  for (let index = 0; index < frames; index++) {
    const time = index / rate;
    const part = Math.min(frequencies.length - 1, Math.trunc(time / 0.24));
    const phase = time % 0.24;
    const envelope = Math.min(1, phase / 0.01) * Math.exp(-phase * 12);
    result.writeInt16LE(
      Math.trunc(Math.sin(time * frequencies[part] * 2 * Math.PI) * envelope * 10000),
      44 + index * 2,
    );
  }
  const path = new URL(`task_notice_${name}.wav`, directory);
  if (check) {
    if (!(await readFile(path)).equals(result))
      throw new Error(`Built-in notification sound differs: ${fileURLToPath(path)}`);
  } else await writeFile(path, result);
}
console.log(
  JSON.stringify({
    sounds: Object.keys(tones),
    format: 'mono PCM16 WAVE, 22050 Hz',
    checked: check,
  }),
);
