import assert from 'node:assert/strict';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const source = await readFile('apps/ios/Sources/EasyWorkSession.swift', 'utf8');
function method(name, next) {
  const a = source.indexOf(`  private func ${name}`);
  const b = source.indexOf(`  private func ${next}`, a);
  assert(a >= 0 && b > a);
  return source.slice(a, b);
}
let implementation = method('finishConnection', 'performConnect').replace(
  '.seconds(30)',
  '.milliseconds(250)',
);
const swift = `import Foundation
actor Fixture {
 var transport: Bool?; var keyConfirmed=false; var activeCandidate: String?; var closed=false
 var connectionTask: (id: UUID, task: Task<Void,Never>)?; var connectionDeadline: Task<Void,Never>?
 var connectionResults: [UUID:Result<Void,Error>]=[:]; var connectionWaiters:[UUID:Int]=[:]
 var attempts=0; var disconnects=0; var gate=false
 func disconnect() async { disconnects += 1; transport=nil; keyConfirmed=false }
 ${implementation}
 private func performConnect(excluding:String?,owner:UUID) async throws {
   attempts += 1
   while !gate { await withCheckedContinuation { c in DispatchQueue.global().asyncAfter(deadline: .now() + 0.01) { c.resume() } } }
   try requireConnectionOwner(owner); transport=true; keyConfirmed=true
 }
 func run() async throws { try await connect() }
 func release() {gate=true}
 func stats()->(Int,Int,Bool) {(attempts,disconnects,keyConfirmed)}
}
@main struct Main {
 static func main() async throws {
  let f=Fixture()
  let first=Task { try await f.run() }; try await Task.sleep(for:.milliseconds(35))
  let second=Task { try await f.run() }; let write=Task { try await f.run() }
  first.cancel(); try await Task.sleep(for:.milliseconds(35)); await f.release()
  do {try await first.value; fatalError("first not cancelled")} catch is CancellationError {}
  try await second.value; try await write.value
  let a=await f.stats(); precondition(a.0==1 && a.2)
  let g=Fixture(); let x=Task {try await g.run()}; let y=Task {try await g.run()}
  for task in [x,y] { do {try await task.value; fatalError("deadline missing")} catch let e as URLError {precondition(e.code == .timedOut)} }
  let z=Task {try await g.run()}; try await Task.sleep(for:.milliseconds(40)); await g.release(); try await z.value
  try await Task.sleep(for:.milliseconds(120)); let b=await g.stats(); precondition(b.0==2 && b.1==1 && b.2)
  print("PASS: shared waiter cancellation; deadline broadcast; late old owner cannot retire new connection")
 }
}`;
const dir = await mkdtemp(join(tmpdir(), 'life01-transport-'));
try {
  await writeFile(join(dir, 'owner.swift'), swift, { mode: 0o600 });
  execFileSync(
    'swiftc',
    ['-parse-as-library', join(dir, 'owner.swift'), '-o', join(dir, 'owner')],
    {
      stdio: 'inherit',
    },
  );
  execFileSync(join(dir, 'owner'), [], { stdio: 'inherit' });
} finally {
  await rm(dir, { recursive: true, force: true });
}
