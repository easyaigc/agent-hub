import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const run = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));
function declaration(source, marker) {
  const start = source.indexOf(marker);
  assert(start >= 0, `Missing production declaration: ${marker}`);
  let depth = 0;
  let opened = false;
  for (let index = start; index < source.length; index++) {
    if (source[index] === '{') {
      depth++;
      opened = true;
    }
    if (source[index] === '}' && --depth === 0 && opened) return source.slice(start, index + 1);
  }
  assert.fail(`Unclosed production declaration: ${marker}`);
}

test(
  'restored first creation detaches unchanged content and preserves real concurrent edits',
  {
    skip: process.platform !== 'darwin',
  },
  async (t) => {
    const [models, notifications, session, uploads, host] = await Promise.all([
      readFile(join(repository, 'apps/ios/Sources/Models.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/NotificationModels.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/SessionStore.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/Uploads.swift'), 'utf8'),
      readFile(join(repository, 'apps/ios/Sources/HostService.swift'), 'utf8'),
    ]);
    const captureStart = session.indexOf('let captured = FirstMessageCreationIntent(version: 1,');
    const captureEnd = session.indexOf(
      '\n          guard firstCreation == nil || firstCreation == captured',
      captureStart,
    );
    assert(
      captureStart >= 0 && captureEnd > captureStart,
      'Missing production captured initializer',
    );
    const captureInitializer = session.slice(captureStart, captureEnd);
    await mkdir(join(repository, '.local'), { recursive: true, mode: 0o700 });
    const directory = await mkdtemp(join(repository, '.local/first-creation-restore-'));
    let passed = false;
    t.after(async () => {
      if (passed) await rm(directory, { recursive: true, force: true });
      else {
        await rm(binary, { force: true });
        console.error(`Failed Swift input retained: ${swift}`);
      }
    });
    const swift = join(directory, 'RestoreDraft.swift');
    const binary = join(directory, 'restore-draft');
    await writeFile(
      swift,
      `import Foundation
import CryptoKit
enum L10n { static func tr(_ text: String) -> String { text } }
enum HostServiceError: Error { case invalidResponse, wrongHost, message(String) }
struct Conversation { let id: String; let projectId: String? }
${declaration(notifications, 'struct AgentAccount:')}
${declaration(notifications, 'struct AgentRuntimeTarget:')}
${declaration(models, 'struct LocalAttachment:')}
${declaration(models, 'struct Draft:')}
${declaration(models, 'struct DesktopCreationFile:')}
${declaration(models, 'struct FirstMessageCreationIntent:')}
struct Connection { var target: AgentRuntimeTarget }
struct Client { let id: String; var agentConnection: Connection? = nil; var codexDirect: Connection? = nil; var readingTarget: AgentRuntimeTarget? = nil }
enum HostService { ${declaration(host, 'static func hash(')} }
enum UploadCenter { ${declaration(uploads, 'nonisolated static func identifier(')} }
struct GCDesktop { var attachments: [LocalAttachment] }
struct GCReply { var sourceAttachments: [LocalAttachment]? }
struct GCDetachedReply { var original: GCReply }
struct GCDetachedFirst { var original: FirstMessageCreationIntent }
struct GCDrafts { var values: [String: Draft] = [:] }
struct GCSaved {
 var firstMessageCreations: [String: FirstMessageCreationIntent]? = nil
 var detachedFirstMessageCreations: [String: [GCDetachedFirst]]? = nil
 var desktopCreations: [String: GCDesktop]? = nil
 var detachedDesktopCreations: [String: [GCDesktop]]? = nil
 var outbox: [String: GCReply]? = nil
 var detachedReplies: [String: [GCDetachedReply]]? = nil
}
struct GCFixture {
 var drafts = GCDrafts(); var saved = GCSaved(); let directory: URL
 func attachmentURL(_ file: LocalAttachment) -> URL { directory.appendingPathComponent(file.fileName) }
 ${declaration(session, 'private func removeUnreferencedAttachment(')}
 func collect(_ file: LocalAttachment) { removeUnreferencedAttachment(file) }
}
struct ProjectFixture { let id: String }
struct DraftValues { var values: [String: Draft] }
func recapture(_ firstCreation: FirstMessageCreationIntent?, content: Draft, target: AgentRuntimeTarget,
  manifest: [DesktopCreationFile], project: String?, selectedMode: String?, selectedModel: String?,
  selectedEffort: String?, selectedPermission: String?, selectedSpeed: String?) -> FirstMessageCreationIntent {
  let drafts = DraftValues(values: [:]); let oldKey = "key"; let selectedProject = project.map { ProjectFixture(id: $0) }
  ${captureInitializer}
  return captured
}
struct AttachmentDraftDestination { let key: String; let generation: String }
struct DraftImportDestination: Hashable { let target: String; let generation: String? }
struct ImportFixture {
  var imports: [String: Int] = [:]
  var recoveringAttachmentKeys = Set<String>()
  var localImportTargets: [String: AttachmentDraftDestination] = [:]
  var scopedImports: [DraftImportDestination: Int] = [:]
  ${declaration(session, 'private func hasPendingAttachmentImports(')}
  func blocked(_ target: String) -> Bool { hasPendingAttachmentImports(target) }
}
var imports = ImportFixture()
precondition(!imports.blocked("destination"))
imports.imports["destination"] = 1
precondition(imports.blocked("destination") && !imports.blocked("other"))
imports.imports["destination"] = 0
imports.recoveringAttachmentKeys.insert("destination")
precondition(imports.blocked("destination"))
imports.recoveringAttachmentKeys.removeAll()
imports.localImportTargets["retired-job"] = AttachmentDraftDestination(key: "destination", generation: "retired-generation")
precondition(imports.blocked("destination"))
imports.localImportTargets.removeAll()
imports.scopedImports[DraftImportDestination(target: "destination", generation: "retired-generation")] = 1
precondition(imports.blocked("destination"))
imports.scopedImports.removeAll()
precondition(!imports.blocked("destination"))

let file = LocalAttachment(id: "original-file", name: "original.txt", size: 3,
  fileName: "original-retained-file", isImage: false, photoAssetId: "asset")
let snapshot = Draft(text: "original content", attachments: [file],
  attachmentImportGeneration: "original-import-generation", desktopOpenOperationId: "original-open",
  collaborationMode: "office", model: "original-model", effort: "medium", permissionMode: "confirm_high_risk", speed: "fast")
let target = AgentRuntimeTarget(agentId: "easywork", hostId: "host", workspaceId: "workspace",
  runtimeId: "runtime", account: AgentAccount(authority: "https://issuer.example", subject: "fixture"))
var intent = FirstMessageCreationIntent(version: 1, operationId: "original-operation", messageId: "original-message",
  target: target, projectId: "original-project", text: snapshot.text,
  manifest: [DesktopCreationFile(id: file.id, name: file.name, mediaType: "text/plain", sizeBytes: 3, sha256: "original-hash")],
  attachments: [file], metadataSubmitted: true, sourceDraft: snapshot,
  collaborationMode: snapshot.collaborationMode, model: snapshot.model, effort: snapshot.effort,
  permissionMode: snapshot.permissionMode, speed: snapshot.speed)
let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
let originalBytes = try encoder.encode(intent)
intent = try JSONDecoder().decode(FirstMessageCreationIntent.self, from: originalBytes)
let restored = intent.restoredDraft()
precondition(restored.attachmentImportGeneration == snapshot.attachmentImportGeneration)
precondition(restored.desktopOpenOperationId == snapshot.desktopOpenOperationId)
precondition(restored.operationId == intent.operationId && restored.attachments == intent.attachments)
precondition(intent.matchesSubmittedDraft(restored))
precondition(intent.draftAfterMovingOriginal(restored) == nil)
var edited = restored; edited.text = "new edited content"
let remaining = intent.draftAfterMovingOriginal(edited)!
precondition(remaining.text == edited.text && remaining.operationId == nil)
precondition(remaining.attachments.count == 1 && remaining.attachments[0].id != file.id)
precondition(remaining.attachments[0].fileName == file.fileName)
precondition(remaining.attachments[0].name == file.name && remaining.attachments[0].size == file.size)
precondition(remaining.attachments[0].isImage == file.isImage && remaining.attachments[0].photoAssetId == file.photoAssetId)
let bytes = Data("abc".utf8)
let originalURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try bytes.write(to: originalURL); defer { try? FileManager.default.removeItem(at: originalURL) }
let readBytes = try Data(contentsOf: originalURL)
let retainedBytes = remaining.attachments[0].fileName == file.fileName ? try Data(contentsOf: originalURL) : Data()
precondition(readBytes == bytes && retainedBytes == bytes)
let client = Client(id: "host", readingTarget: target)
func transferId(_ attachment: LocalAttachment) -> String {
  UploadCenter.identifier(client, purpose: "attachment", parent: "thread", fileId: attachment.id)
}
precondition(transferId(file) != transferId(remaining.attachments[0]))
precondition(transferId(file) == transferId(restored.attachments[0]))
precondition(remaining.attachmentImportGeneration == snapshot.attachmentImportGeneration)
var newerImport = restored; newerImport.attachmentImportGeneration = "new-import-generation"
precondition(intent.draftAfterMovingOriginal(newerImport)?.attachmentImportGeneration == "new-import-generation")
var newSettings = restored; newSettings.speed = "standard"
precondition(intent.draftAfterMovingOriginal(newSettings)?.speed == "standard")
// The production collector must preserve shared bytes for each independent reference.
let gcDirectory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: gcDirectory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: gcDirectory) }
let gcFile = gcDirectory.appendingPathComponent(file.fileName)
let gcControl = gcDirectory.appendingPathComponent("control")
try bytes.write(to: gcFile); try Data("control".utf8).write(to: gcControl)
var gc = GCFixture(directory: gcDirectory)
func retained() -> Bool { (try? Data(contentsOf: gcFile)) == bytes }
gc.saved.firstMessageCreations = ["pending": intent]; gc.collect(file); precondition(retained())
gc.saved.firstMessageCreations = nil
gc.saved.detachedFirstMessageCreations = ["detached": [GCDetachedFirst(original: intent)]]; gc.collect(file); precondition(retained())
gc.saved.detachedFirstMessageCreations = nil
var cloneDraft = Draft(); cloneDraft.attachments = remaining.attachments
gc.drafts.values = ["concurrent": cloneDraft]; gc.collect(file); precondition(retained())
gc.drafts.values = [:]
gc.saved.outbox = ["outbox": GCReply(sourceAttachments: [file])]; gc.collect(file); precondition(retained())
gc.saved.outbox = nil
gc.collect(file); precondition(!FileManager.default.fileExists(atPath: gcFile.path))
let controlBytes = try Data(contentsOf: gcControl); precondition(controlBytes == Data("control".utf8))
let afterBytes = try encoder.encode(intent)
precondition(afterBytes == originalBytes)
// The submitted request can carry an effective default while the source
// draft deliberately leaves its override unset. Neither a nil override nor
// an explicit source choice may be overwritten by the effective request.
var inheritedSnapshot = snapshot
inheritedSnapshot.collaborationMode = nil
inheritedSnapshot.model = nil
inheritedSnapshot.effort = nil
inheritedSnapshot.permissionMode = nil
inheritedSnapshot.speed = nil
var inheritedIntent = FirstMessageCreationIntent(version: 1, operationId: intent.operationId,
  messageId: intent.messageId, target: target, projectId: intent.projectId,
  text: inheritedSnapshot.text, manifest: intent.manifest, attachments: inheritedSnapshot.attachments,
  metadataSubmitted: true, sourceDraft: inheritedSnapshot,
  collaborationMode: "default", model: "effective-model", effort: "medium",
  permissionMode: "confirm_high_risk", speed: "standard")
let inheritedBytes = try encoder.encode(inheritedIntent)
inheritedIntent = try JSONDecoder().decode(FirstMessageCreationIntent.self, from: inheritedBytes)
let inheritedRestored = inheritedIntent.restoredDraft()
precondition(inheritedRestored.collaborationMode == nil && inheritedRestored.model == nil)
precondition(inheritedRestored.effort == nil && inheritedRestored.permissionMode == nil && inheritedRestored.speed == nil)
precondition(inheritedIntent.matchesSubmittedDraft(inheritedRestored))
precondition(inheritedIntent.draftAfterMovingOriginal(inheritedRestored) == nil)
let inheritedAfterBytes = try encoder.encode(inheritedIntent)
precondition(inheritedAfterBytes == inheritedBytes)
var changedInherited = inheritedRestored; changedInherited.collaborationMode = "office"
precondition(inheritedIntent.draftAfterMovingOriginal(changedInherited)?.collaborationMode == "office")
var explicitIntent = inheritedIntent; explicitIntent.sourceDraft = snapshot
precondition(explicitIntent.restoredDraft().collaborationMode == snapshot.collaborationMode)
precondition(explicitIntent.restoredDraft().model == snapshot.model)
precondition(explicitIntent.restoredDraft().speed == snapshot.speed)
precondition(explicitIntent.matchesSubmittedDraft(explicitIntent.restoredDraft()))
var legacy = intent; legacy.sourceDraft = nil
let legacyData = try encoder.encode(legacy)
legacy = try JSONDecoder().decode(FirstMessageCreationIntent.self, from: legacyData)
let legacyRestored = legacy.restoredDraft()
let recapturedLegacy = recapture(legacy, content: legacyRestored, target: target, manifest: legacy.manifest,
  project: legacy.projectId, selectedMode: legacy.collaborationMode, selectedModel: legacy.model,
  selectedEffort: legacy.effort, selectedPermission: legacy.permissionMode, selectedSpeed: legacy.speed)
precondition(recapturedLegacy.sourceDraft == nil, "Legacy missing sourceDraft must stay nil")
precondition(recapturedLegacy == legacy, "Legacy immutable captured intent must remain equal")
let recapturedExisting = recapture(intent, content: restored, target: target, manifest: intent.manifest,
  project: intent.projectId, selectedMode: intent.collaborationMode, selectedModel: intent.model,
  selectedEffort: intent.effort, selectedPermission: intent.permissionMode, selectedSpeed: intent.speed)
precondition(recapturedExisting == intent && recapturedExisting.sourceDraft == snapshot)
let fresh = recapture(nil, content: snapshot, target: target, manifest: intent.manifest,
  project: intent.projectId, selectedMode: intent.collaborationMode, selectedModel: intent.model,
  selectedEffort: intent.effort, selectedPermission: intent.permissionMode, selectedSpeed: intent.speed)
precondition(fresh.sourceDraft == snapshot)
precondition(legacyRestored.text == intent.text && legacyRestored.operationId == intent.operationId)
precondition(!legacy.matchesSubmittedDraft(legacyRestored))
precondition(legacy.draftAfterMovingOriginal(legacyRestored) != nil)
print("first creation restore: unchanged, edited, import-generation, settings, durable intent and legacy cases passed")
`,
    );
    try {
      await run('swiftc', [swift, '-o', binary], { cwd: repository, timeout: 120_000 });
      const result = await run(binary, [], { cwd: repository, timeout: 30_000 });
      assert.match(result.stdout, /durable intent and legacy cases passed/);
      passed = true;
    } catch (error) {
      await writeFile(
        join(directory, 'failure.json'),
        JSON.stringify({ message: error.message, stderr: error.stderr ?? '', swift }, null, 2),
        { mode: 0o600 },
      );
      throw error;
    }
  },
);
