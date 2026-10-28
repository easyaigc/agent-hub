import assert from 'node:assert/strict';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
const root = fileURLToPath(new URL('../../../../../../../', import.meta.url));
const out = resolve(process.argv[2]);
await mkdir(out, { mode: 0o700 });
const source = await readFile(join(root, 'apps/ios/Sources/EasyWorkTransport.swift'), 'utf8');
const functions = source.split('\n').filter((s) => s.startsWith('func easyWorkJSON('));
assert.equal(functions.length, 1);
const script = `import Foundation
import CryptoKit
${functions[0]}
let data=try Data(contentsOf:URL(fileURLWithPath:CommandLine.arguments[1]))
let root=try JSONSerialization.jsonObject(with:data) as! [String:Any]
var results=[[String:Any]]()
for v in root["vectors"] as! [[String:Any]] {
 let bytes=try easyWorkJSON(v["input"]!)
 let text=String(decoding:bytes,as:UTF8.self)
 let sha=SHA256.hash(data:bytes).map {String(format:"%02x",$0)}.joined()
 guard text==v["canonical"] as! String,sha==v["sha256"] as! String else {fatalError("Canonical mismatch: \\(v["id"]!)")}
 results.append(["id":v["id"]!,"sha256":sha])
}
let result:[String:Any]=["vectors":results,"passed":results.count,"scope":"macOS Foundation executing exact current iOS serialization function; no iOS App/device run"]
try JSONSerialization.data(withJSONObject:result,options:[.sortedKeys,.prettyPrinted,.withoutEscapingSlashes]).write(to:URL(fileURLWithPath:CommandLine.arguments[2]))
print("Swift Foundation vectors passed: \\(results.count)")
`;
await writeFile(join(out, 'check.swift'), script, { flag: 'wx', mode: 0o600 });
await writeFile(
  join(out, 'source.json'),
  JSON.stringify(
    {
      iosSourceSha256: createHash('sha256').update(source).digest('hex'),
      function: functions[0],
      logicEdits: 0,
    },
    null,
    2,
  ) + '\n',
  { flag: 'wx', mode: 0o600 },
);
const result = spawnSync(
  'xcrun',
  [
    'swift',
    '-module-cache-path',
    join(out, 'module-cache'),
    join(out, 'check.swift'),
    join(root, 'apps/android/src/androidTest/assets/easywork-canonical-keys-v1.json'),
    join(out, 'results.json'),
  ],
  { encoding: 'utf8', timeout: 60000 },
);
process.stdout.write(result.stdout ?? '');
process.stderr.write(result.stderr ?? '');
assert.equal(result.status, 0);
