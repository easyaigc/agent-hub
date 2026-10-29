#!/usr/bin/env python3
"""Compile exact rename/replace/update source slices; never starts Android or a network peer."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def part(text, start, end):
    assert text.count(start) == 1, start
    a = text.index(start)
    b = text.index(end, a)
    return text[a:b]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['repo', 'session-source', 'output', 'gradle-cache', 'android-jar', 'java', 'build-slot']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--variant', choices=['base', 'proposed'], required=True)
    args = parser.parse_args()
    assert Path(args.android_jar).is_file(), "Exact installed platform android.jar required"
    root = Path(args.repo).resolve()
    here = Path(__file__).resolve().parent
    output = Path(args.output).resolve()
    output.mkdir(mode=0o700)
    sources = output / 'sources'
    sources.mkdir(mode=0o700)
    session = Path(args.session_source).read_text()
    manifest = json.loads((here / 'session-overlay.json').read_text())
    expected = manifest['baseSha256' if args.variant == 'base' else 'proposedSha256']
    assert digest(session.encode()) == expected, 'Select the exact shared base/proposed source'
    rename = part(session, '    fun renameEasyWork(', '    internal suspend fun updateEasyWorkCandidates(')
    method = part(session, '    internal suspend fun updateEasyWorkCandidates(', '    suspend fun connectDiscoveredEasyWork(')
    assert digest(rename.encode()) == manifest['originalMethodSha256' if args.variant == 'base' else 'proposedMethodSha256']
    replace = part(session, '    private fun replaceClient(', '    suspend fun credential(')
    client = part(session, 'enum class ConnectionPhase', 'data class PendingAgentLink')
    models = (root / 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkModels.kt').read_text()
    reads = (root / 'apps/android/src/main/kotlin/com/agenthub/android/HostReads.kt').read_text()
    commands = (root / 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt').read_text()
    target = part(commands, '    fun target(value: TargetScope)', '    fun decode(wire:')
    decode = part(reads, '    fun target(json: JSONObject?)', '    fun thread(')
    optional = next(line for line in reads.splitlines() if line.startswith('internal fun JSONObject.optional('))
    objects = next(line for line in reads.splitlines() if line.startswith('internal fun JSONArray.objects('))
    strings = next(line for line in reads.splitlines() if line.startswith('internal fun JSONArray.strings('))
    inputs = {}
    def save(name, raw):
        path = sources / name
        path.write_text(raw)
        inputs[name] = digest(raw.encode())
    save('EasyWorkModels.kt', models)
    save('MobileClient.kt', 'package com.agenthub.android\nimport com.agenthub.core.TargetScope\n' + client)
    save('Codec.kt', 'package com.agenthub.android\nimport com.agenthub.core.*\nimport org.json.JSONObject\nimport org.json.JSONArray\n' + optional + '\n' + objects + '\n' + strings + '\nobject CodexCommandCodec {\n' + target + '}\nobject HostReadCodec {\n' + decode + '}\ninternal fun sameScope(a: TargetScope?,b: TargetScope?)=RuntimeIdentity.matches(a,b)\n')
    save('MigratedEasyWorkConnection.kt', (root / 'apps/android/src/main/kotlin/com/agenthub/android/MigratedEasyWorkConnection.kt').read_text())
    wire = (root / 'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationWire.kt').read_text()
    keys = part(wire, '    fun keys(', '    fun text(')
    save('StrictKeys.kt', 'package com.agenthub.android\nimport org.json.JSONObject\ninternal object NativeChatNotificationWire {\n' + keys + '}\n')
    # Only unused platform holder types are represented here. No identity/storage/crypto behavior.
    save('PlatformHolders.kt', '''package com.agenthub.android
class PairedCodexConnection
class MobileStorageIdentity
class EasyWorkIdentity(val deviceId: String,val privateKey: String,val publicKey: String)
class NotificationHolder {fun updateComputers(clients: List<MobileClient>){};fun reconcile(){}}
''')
    save('CandidateUpdateSession.kt', '''package com.agenthub.android
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
class HeldCredentialStore {
 val rows=ConcurrentHashMap<String,String>();val entered=CountDownLatch(1);val release=CountDownLatch(1)
 fun read(key: String): String? {val captured=rows[key];entered.countDown();check(release.await(2,TimeUnit.SECONDS));return captured}
}
class CandidateUpdateSession(val realClients: MutableList<MobileClient>,val storage: HeldCredentialStore?) {
 val revisions=mutableMapOf<String,Long>();var selectedClient=realClients.first();var publishes=0
 val draft="untouched original draft";val connectionBusy=true;val chatNotifications: NotificationHolder?=null
 private fun synchronizeReadingPresentation(){};private fun reconcilePhoneReading(){}
 val persistedNames=mutableListOf<List<String>>()\n private fun persistMetadata(){publishes++;persistedNames.add(realClients.map {it.name})}
''' + replace + rename + method + '}\n')
    for name in ['MobileRules.kt', 'RuntimeIdentity.kt', 'DomainContract.kt']:
        save(name, (root / 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core' / name).read_text())
    generated = 'package com.agenthub.core\n'
    for field, file in [('managementSchemaBase64', 'agent-management-v1.schema.json'), ('codexHttpSchemaBase64', 'codex-mobile-http-v1.schema.json'), ('codexDiscoverySchemaBase64', 'codex-host-discovery-v1.schema.json')]:
        raw = (root / 'packages/protocol/schemas' / file).read_bytes()
        encoded = base64.b64encode(raw).decode()
        generated += 'internal val ' + field + ' = listOf(' + ','.join(json.dumps(encoded[i:i + 8192]) for i in range(0, len(encoded), 8192)) + ').joinToString("")\n'
    save('ManagementSchema.kt', generated)
    save('EasyWorkRenameBoundaryTest.kt', (here / 'jvm/EasyWorkRenameBoundaryTest.kt').read_text())
    cache = Path(args.gradle_cache)
    def jar(group, name, version):
        found = list((cache / group / name / version).glob('*/*.jar'))
        assert len(found) == 1, (group, name, version, found)
        return found[0]
    std = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21')
    coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.11.0')
    annotations = jar('org.jetbrains', 'annotations', '13.0')
    compiler = [jar('org.jetbrains.kotlin', name, version) for name, version in [('kotlin-compiler-embeddable', '2.4.21'), ('kotlin-script-runtime', '2.4.21'), ('kotlin-reflect', '1.6.10'), ('kotlin-daemon-embeddable', '2.4.21')]] + [std, coroutines, annotations]
    runtime = [std, coroutines, annotations, jar('org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.11.0'), jar('org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.11.0'), jar('org.json', 'json', '20250517'), jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core', '1.3')]
    classes = output / 'classes'
    classes.mkdir(mode=0o700)
    cp = lambda paths: os.pathsep.join(map(str, paths))
    compile_args = [args.java, '-cp', cp(compiler), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp(runtime + [Path(args.android_jar)]), '-d', str(classes), *map(str, sorted(sources.glob('*.kt')))]
    def run(name, argv):
        with (output / (name + '.log')).open('wb') as stream:
            return subprocess.run([sys.executable, args.build_slot, *argv], stdout=stream, stderr=subprocess.STDOUT, timeout=180).returncode
    compiled = run('compile', compile_args)
    tested = None
    if compiled == 0:
        tested = run('junit', [args.java, '-Dp8.asset=' + str(root / 'apps/android/src/androidTest/assets/easywork-crypto-vector.json'), '-cp', cp([classes] + runtime), 'org.junit.runner.JUnitCore', 'com.agenthub.android.EasyWorkRenameBoundaryTest'])
    result = {'variant': args.variant, 'sourceSha256': expected, 'exactMethodSha256': digest(rename.encode()), 'unchangedUpdateMethodSha256': digest(method.encode()), 'exactReplaceMethodSha256': digest(replace.encode()), 'generatedSourceHashes': inputs, 'dependencies': {str(path): digest(path.read_bytes()) for path in compiler + runtime + [Path(args.android_jar)]}, 'compileExit': compiled, 'junitExit': tested, 'scope': 'Exact production rename/replace/update/models/core/schema in synthetic outer state, metadata-name witness and held IO; not MobileSession/native storage/UI/network acceptance', 'deviceActions': 0}
    (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: result[k] for k in ['variant', 'exactMethodSha256', 'compileExit', 'junitExit']}))
    return compiled if compiled else tested


if __name__ == '__main__':
    sys.exit(main())
