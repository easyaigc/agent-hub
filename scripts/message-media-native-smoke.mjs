import { readFile, mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { resolve, join } from 'node:path';

const source = await readFile('apps/ios/Sources/MessageLinks.swift', 'utf8');
const start = source.indexOf('enum MessageMediaMarkdown {');
const end = source.indexOf('\nstruct MarkdownParagraph:', start);
if (start < 0 || end < 0) throw Error('Production parser boundaries missing');
const root = resolve('.local/multimedia-20261120/verification');
await mkdir(root, { recursive: true, mode: 0o700 });
const dir = await mkdtemp(join(root, 'parser-'));
const test = String.raw`
func check(_ name: String, _ input: String, _ expected: [MessageMediaMarkdown.Part]) {
  let actual = MessageMediaMarkdown.parts(input)
  if actual != expected { print("FAIL \(name): \(actual)"); exit(1) }
  print("PASS \(name)")
}
let image = MessageMediaMarkdown.Kind.image
let video = MessageMediaMarkdown.Kind.video
let audio = MessageMediaMarkdown.Kind.audio
check("mixed", "前 ![图](a.png) 中 ![影片](b.MOV) 后 ![音](c.mp3)", [.text("前 "), .media(title: "图", reference: "a.png", kind: image), .text(" 中 "), .media(title: "影片", reference: "b.MOV", kind: video), .text(" 后 "), .media(title: "音", reference: "c.mp3", kind: audio)])
check("inline code", "\u{60}![x](a.png)\u{60}", [.text("\u{60}![x](a.png)\u{60}")])
check("fenced code", "\u{60}\u{60}\u{60}swift\n![x](a.png)\n\u{60}\u{60}\u{60}", [.text("\u{60}\u{60}\u{60}swift\n![x](a.png)\n\u{60}\u{60}\u{60}")])
check("escaped", #"\![x](a.png)"#, [.text(#"\![x](a.png)"#)])
check("balanced", "![a[b]c](path/a(b).png)", [.media(title: "a[b]c", reference: "path/a(b).png", kind: image)])
check("angle unicode spaces title", "![中文](<目录/图 像.png> \"标题\")", [.media(title: "中文", reference: "目录/图 像.png", kind: image)])
check("escaped destination", #"![a\]b](path/a\(b\).png 'title')"#, [.media(title: "a]b", reference: "path/a(b).png", kind: image)])
for bad in ["![x](", "![x](<bad.png)", "![x](a.png \"unfinished)", "![x](https://u:p@example.com/a.png)"] { check("malformed " + bad, bad, [.text(bad)]) }
let long = String(repeating: "字", count: 100_000) + " ![x](end.png)"
check("long", long, [.text(String(repeating: "字", count: 100_000) + " "), .media(title: "x", reference: "end.png", kind: image)])
let oversized = "![" + String(repeating: "a", count: 9000) + "](a.png)"
check("bounded malformed", oversized, [.text(oversized)])
`;
try {
  const swift = join(dir, 'main.swift');
  await writeFile(swift, 'import Foundation\n' + source.slice(start, end) + '\n' + test);
  const compile = spawnSync('swiftc', [swift, '-o', join(dir, 'smoke')], { stdio: 'inherit' });
  if (compile.status !== 0) process.exitCode = compile.status || 1;
  else {
    const run = spawnSync(join(dir, 'smoke'), [], { stdio: 'inherit', timeout: 30000 });
    if (run.status !== 0) process.exitCode = run.status || 1;
  }
} finally {
  await rm(dir, { recursive: true, force: true });
}
