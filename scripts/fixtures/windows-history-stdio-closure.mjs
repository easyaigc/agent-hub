import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import net from 'node:net';
import { createInterface } from 'node:readline';
import assert from 'node:assert/strict';
process.umask(0o077);
assert.equal(process.platform, 'win32');
const program = resolve(process.argv[2]);
const platform = await import(
  pathToFileURL(join(program, 'packages/codex-adapter/dist/desktop-platform.js'))
);
const { CodexHistoryReader } = await import(
  pathToFileURL(join(program, 'packages/codex-adapter/dist/history-reader.js'))
);
const base = join(program, '.local');
fs.mkdirSync(base, { recursive: true });
const dir = fs.mkdtempSync(join(base, 'native-stdin-fixture-'));
platform.protectWindowsStateDirectory(dir);
const nonce = randomUUID();
const cs = String.raw`
using System;using System.IO;using System.Text;using System.Net;using System.Net.Sockets;using System.Runtime.InteropServices;using System.Web.Script.Serialization;using Microsoft.Win32.SafeHandles;using System.Collections.Generic;
class NativeStdinFixture {
 [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr GetStdHandle(int kind);
 [DllImport("kernel32.dll",SetLastError=true)]static extern uint GetFileType(IntPtr handle);
 static int Main(string[] args){try{
  var json=new JavaScriptSerializer();Console.OutputEncoding=new UTF8Encoding(false);string nonce=Environment.GetEnvironmentVariable("NATIVE_STDIN_FIXTURE_NONCE");
  if(String.IsNullOrEmpty(nonce))return 2;
  IntPtr raw=GetStdHandle(-10);using(var handle=new SafeFileHandle(raw,true))using(var input=new StreamReader(new FileStream(handle,FileAccess.Read),new UTF8Encoding(false))){
   while(true){string line=input.ReadLine();if(line==null)return 3;var r=(Dictionary<string,object>)json.DeserializeObject(line);string method=(string)r["method"];
    if(method=="initialize"){Console.WriteLine(json.Serialize(new{id=r["id"],result=new{userAgent="owned-native-stdin-fixture"}}));Console.Out.Flush();}
    else if(method=="initialized")break;else return 4;
   }
   var listener=new TcpListener(IPAddress.Loopback,0);listener.Start(1);Console.WriteLine(json.Serialize(new{fixture="native-stdin",port=((IPEndPoint)listener.LocalEndpoint).Port}));Console.Out.Flush();
   using(var client=listener.AcceptTcpClient()){listener.Stop();client.ReceiveTimeout=10000;client.SendTimeout=10000;using(var stream=client.GetStream())using(var reader=new StreamReader(stream,new UTF8Encoding(false),false,1024,true))using(var writer=new StreamWriter(stream,new UTF8Encoding(false),1024,true)){writer.AutoFlush=true;
    if(reader.ReadLine()!=nonce+":CLOSE_OWN_STDIN")return 5;
    input.Dispose();handle.Dispose();uint type=GetFileType(raw);int error=Marshal.GetLastWin32Error();if(type!=0||error!=6)return 6;
    writer.WriteLine(json.Serialize(new{closed=true,invalidHandleError=error}));
    // EOF from this one parent retires the owned fixture even if the parent dies.
    reader.ReadLine();
   }}
  }return 0;
 }catch(Exception e){Console.Error.WriteLine(e.GetType().Name);return 1;}}
}`;
const source = join(dir, 'fixture.cs'),
  exe = join(dir, 'fixture.exe');
fs.writeFileSync(source, cs, { flag: 'wx', mode: 0o600 });
platform.protectWindowsStateFile(source);
const compile = spawnSync(
  join(process.env.SystemRoot, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'),
  ['/nologo', '/platform:x64', '/r:System.Web.Extensions.dll', '/out:' + exe, source],
  { windowsHide: true, stdio: 'pipe', timeout: 30000 },
);
fs.writeFileSync(join(dir, 'compile.stdout'), compile.stdout ?? '');
fs.writeFileSync(join(dir, 'compile.stderr'), compile.stderr ?? '');
assert.equal(compile.status, 0, 'Native fake must compile');
platform.protectWindowsStateFile(exe);
const oldNonce = process.env.NATIVE_STDIN_FIXTURE_NONCE;
process.env.NATIVE_STDIN_FIXTURE_NONCE = nonce;
const reader = new CodexHistoryReader('owned-fixture-host', 'Native closed pipe', exe);
let child,
  socket,
  childClosed = false,
  disconnections = 0;
const streamErrors = [];
const report = {
  scope: 'OWNED_NATIVE_STDIN_ONLY_NOT_PRODUCT_OR_PHONE_ACCEPTANCE',
  productionRuntimeChanged: false,
  modelInputs: 0,
  passed: false,
};
reader.onDisconnect = () => disconnections++;
try {
  await reader.connect();
  child = reader.process;
  assert(child && reader.ready);
  child.once('close', () => (childClosed = true));
  child.stdin.on('error', (e) => streamErrors.push(e.code));
  const closedPromise = reader.processClosed;
  const port = await new Promise((done, reject) => {
    const timer = setTimeout(() => reject(Error('Fixture readiness deadline')), 5000);
    const lines = createInterface({ input: child.stdout });
    lines.on('line', (line) => {
      let x;
      try {
        x = JSON.parse(line);
      } catch {
        return;
      }
      if (x.fixture === 'native-stdin' && Number.isInteger(x.port)) {
        clearTimeout(timer);
        lines.close();
        done(x.port);
      }
    });
  });
  await new Promise((done, reject) => child.stdin.write('', (e) => (e ? reject(e) : done())));
  const closed = await new Promise((done, reject) => {
    socket = net.connect({ host: '127.0.0.1', port }, () =>
      socket.write(nonce + ':CLOSE_OWN_STDIN\n'),
    );
    socket.setTimeout(5000, () => reject(Error('Native close deadline')));
    socket.on('error', reject);
    const lines = createInterface({ input: socket });
    lines.once('line', (line) => {
      socket.setTimeout(0);
      try {
        done(JSON.parse(line));
      } catch (e) {
        reject(e);
      }
    });
  });
  assert.equal(closed.closed, true);
  assert.equal(closed.invalidHandleError, 6);
  assert.equal(child.exitCode, null, 'Owned child stays alive after actual kernel close');
  report.actualKernelClose = closed;
  try {
    await reader.read('config/read', {});
    throw Error('Closed pipe unexpectedly returned a result');
  } catch (e) {
    report.readError = { message: e.message, code: e.code ?? null };
    assert.match(e.message, /Reader (write failed|transport closed|unavailable)/);
  }
  await new Promise((done) => setImmediate(done));
  assert(
    streamErrors.includes('EPIPE') || streamErrors.includes('ECONNRESET'),
    'Require a real OS pipe error, not a synthetic exception',
  );
  socket.destroy();
  await closedPromise;
  assert.equal(reader.ready, false);
  assert.equal(reader.pending.size, 0);
  assert.equal(disconnections, 1);
  report.passed = true;
  report.noReplay = true;
  report.actualStreamErrors = streamErrors;
  report.disconnections = disconnections;
  report.childClosed = childClosed;
} catch (e) {
  report.error = { name: e.name, code: e.code ?? null, message: e.message };
} finally {
  socket?.destroy();
  reader.close();
  if (child && !childClosed) {
    await Promise.race([reader.processClosed, new Promise((done) => setTimeout(done, 5000))]);
  }
  report.childClosed = childClosed;
  report.ready = reader.ready;
  report.pending = reader.pending.size;
  report.actualStreamErrors = streamErrors;
  report.finishedAt = new Date().toISOString();
  fs.writeFileSync(join(dir, 'result.json'), JSON.stringify(report, null, 2), {
    mode: 0o600,
    flag: 'wx',
  });
  platform.closeWindowsPrivateStateBackend();
  if (oldNonce === undefined) delete process.env.NATIVE_STDIN_FIXTURE_NONCE;
  else process.env.NATIVE_STDIN_FIXTURE_NONCE = oldNonce;
  console.log(JSON.stringify({ directory: dir, ...report }));
  if (!report.passed) process.exitCode = 1;
}
