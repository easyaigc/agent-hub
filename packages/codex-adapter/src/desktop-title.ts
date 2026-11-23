/** Match the desktop's plain, single-line title presentation at both read sources. */
export function desktopTitle(title: string): string {
  return title
    .replace(/\*\*([^\r\n]+?)\*\*/g, '$1')
    .replace(/\s+/g, ' ')
    .trim();
}
