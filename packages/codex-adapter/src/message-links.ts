import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { join } from 'node:path';

export interface MessageLink {
  /** Destination range, including angle brackets when present. */
  start: number;
  end: number;
  reference: string;
}

/** Inline Markdown destinations, including spaces in <...> and balanced parentheses.
 * Code spans/fences are excluded. The original label, title and surrounding text survive rewrites.
 */
export function messageLinks(text: string): MessageLink[] {
  const links: MessageLink[] = [];
  for (let index = 0; index < text.length; index++) {
    if (text[index] === '\\') {
      index++;
      continue;
    }
    if (text[index] === '`') {
      let end = index + 1;
      while (text[end] === '`') end++;
      const close = text.indexOf(text.slice(index, end), end);
      index = close < 0 ? text.length : close + end - index - 1;
      continue;
    }
    if (text[index] !== ']' || text[index + 1] !== '(') continue;
    let cursor = index + 2;
    while (/[ \t\n]/.test(text[cursor] ?? '')) cursor++;
    const start = cursor;
    const angled = text[cursor] === '<';
    if (angled) cursor++;
    const content = cursor;
    let depth = 0;
    for (; cursor < text.length && cursor - start < 8192; cursor++) {
      const char = text[cursor];
      if (char === '\\' && /[\\()<>]/.test(text[cursor + 1] ?? '')) {
        cursor++;
        continue;
      }
      if (angled) {
        if (char === '>' || char === '\n') break;
      } else {
        if (char === '(') depth++;
        if ((char === ')' && depth === 0) || /\s/.test(char!)) break;
        if (char === ')') depth--;
        if (depth > 32) break;
      }
    }
    const contentEnd = cursor;
    if (angled && text[cursor++] !== '>') continue;
    const end = cursor;
    while (/[ \t\n]/.test(text[cursor] ?? '')) cursor++;
    if (cursor > end && ['"', "'", '('].includes(text[cursor] ?? '')) {
      const quote = text[cursor] === '(' ? ')' : text[cursor];
      cursor++;
      while (cursor < text.length && cursor - end < 8192 && text[cursor] !== quote) {
        if (text[cursor] === '\\') cursor++;
        cursor++;
      }
      cursor++;
      while (/[ \t\n]/.test(text[cursor] ?? '')) cursor++;
    }
    if (text[cursor] !== ')' || contentEnd === content || depth !== 0) continue;
    links.push({
      start,
      end,
      reference: text
        .slice(content, contentEnd)
        .replace(/\\([!"#$%&'()*+,\-./:;<=>?@[\]\\^_`{|}~])/g, '$1'),
    });
    index = cursor;
  }
  return links;
}

/** Interpret only local references. Authorization and fresh byte checks remain with AttachmentFiles. */
export function localMessagePath(reference: string): string | undefined {
  let path = reference;
  if (/^file:/i.test(path)) {
    try {
      const url = new URL(path);
      if (url.search) return undefined;
      path = fileURLToPath(url);
    } catch {
      return undefined;
    }
  } else {
    path = path.replace(/^sandbox:/i, '');
    // Windows drive paths are paths, not URI schemes. Never reinterpret a web/custom URL as a file.
    if (/^[a-z][a-z\d+.-]*:/i.test(path) && !/^[a-z]:[\\/]/i.test(path)) return undefined;
    if (path.startsWith('//') || path.startsWith('#') || path.startsWith('?')) return undefined;
    path = path.replace(/#L\d+(?:C\d+)?(?:-L?\d+(?:C\d+)?)?$/i, '');
    try {
      path = decodeURIComponent(path);
    } catch {
      // Literal percent signs are valid in a filename.
    }
  }
  path = path.replace(/:\d+(?::\d+)?$/, '');
  if (!path || Array.from(path).some((character) => character.charCodeAt(0) < 32)) return undefined;
  if (path.startsWith('~/')) path = join(homedir(), path.slice(2));
  return path;
}
