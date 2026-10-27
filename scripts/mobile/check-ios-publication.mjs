import { readFile, writeFile, mkdir, mkdtemp } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const root = fileURLToPath(new URL('../../', import.meta.url));
if (process.platform !== 'darwin') {
  console.log('iOS publication native boundaries skipped: requires Apple SwiftUI/Combine SDK');
  process.exit(0);
}
const parent = resolve(root, '.local/ios-publication');
await mkdir(parent, { recursive: true, mode: 0o700 });
const directory = await mkdtemp(join(parent, 'run-'));
const source = await readFile(join(root, 'apps/ios/Sources/SessionStore.swift'), 'utf8'),
  models = await readFile(join(root, 'apps/ios/Sources/Models.swift'), 'utf8');
const region = (name) => {
  const start = source.indexOf('// BEGIN: ' + name),
    end = source.indexOf('// END: ' + name);
  assert(start >= 0 && end > start);
  return source.slice(source.indexOf('\n', start) + 1, end);
};
const model = (name) => {
  const value = models.match(new RegExp('(?:^|\\n)struct ' + name + ':[^\\n]*\\{[\\s\\S]*?\\n\\}'));
  assert(value, name);
  return value[0];
};
const names = [
  'PlanStep',
  'ProposedPlan',
  'QuestionOption',
  'ApprovalQuestion',
  'Approval',
  'DeliveryReceipt',
  'GenerationOptions',
  'ThreadRuntime',
];
const issue = source.match(/private struct ConversationSyncIssue[^\n]*/)[0];
const fixture = {
  state: 'idle',
  turnId: 'turn-0',
  plan: [{ step: 'review', status: 'completed' }],
  error: 'prior error',
  approvals: [
    {
      id: 'a',
      threadId: 'thread',
      turnId: 'turn',
      kind: 'question',
      title: '确认',
      detail: 'details',
      questions: [
        {
          id: 'q',
          question: 'why',
          options: [{ label: 'yes', description: 'continue' }],
          isSecret: false,
          multiple: false,
          custom: true,
        },
      ],
      decisions: ['yes'],
      notificationRequestId: 'notification',
      interactionMode: 'async',
      sourceItemId: 'item',
    },
  ],
  receipts: [
    {
      clientMessageId: 'message',
      hostId: 'host',
      threadId: 'thread',
      state: 'completed',
      turnId: 'turn',
      error: 'prior',
      supersededByTurnId: 'other',
      text: '正文',
      attachmentIds: ['file'],
      queueSource: 'desktop',
      queuePresent: true,
      canExecuteNow: false,
      queueDisposition: 'held',
      queueSyncError: 'retry',
      noticeContext: 'historical',
      laterTurnId: 'later',
    },
  ],
  canControl: false,
  collaborationMode: 'default',
  proposedPlan: { id: 'p', turnId: 't', text: 'plan' },
  generationSettings: {
    model: 'model',
    effort: 'xhigh',
    permissionMode: 'read-only',
    speed: 'standard',
  },
  suggestionSourceTurnId: 'source',
};
const vectors = [];
function visit(value, path = []) {
  if (value && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) visit(item, [...path, key]);
  } else {
    const copy = structuredClone(fixture);
    let parent = copy;
    for (const key of path.slice(0, -1)) parent = parent[key];
    parent[path.at(-1)] =
      typeof value === 'boolean'
        ? !value
        : typeof value === 'number'
          ? value + 1
          : value + '_changed';
    vectors.push({ path: path.join('.'), value: copy });
  }
}
visit(fixture);
await writeFile(join(directory, 'runtime-vectors.json'), JSON.stringify({ fixture, vectors }), {
  mode: 0o600,
});
const swift = `import Foundation
import Combine
import SwiftUI
enum L10n { static func tr(_ value: String) -> String { value } }
${names.map(model).join('\n')}
// Opaque target equality is supplied by the existing domain identity contract.
// These stubs isolate publication, not transport/schema/identity validation.
struct AgentRuntimeTarget: Codable, Equatable { var value: String }
struct AgentEventCursor: Codable, Equatable { var target: AgentRuntimeTarget; var threadId: String; var epoch: String; var sequence: Int }
struct SavedSession: Codable { var counter = 0; var contents = ["items": ["old"]]; var agentEventCursors: [String: AgentEventCursor]?; var runtimes: [String: ThreadRuntime]? = [:] }
@MainActor final class PublicationHarness: ObservableObject {
${region('saved-state-publication')}
${issue}
@Published private var conversationSyncFailures: [String: ConversationSyncIssue] = [:]
private var historyGeneration = UUID()
private var loadedHistoryGeneration: UUID?
private var suggestionNeedsFreshHistory = false
${region('history-state-publication')}
}
@MainActor extension PublicationHarness {
  func verify() throws {
    var events: [Int] = []
    let subscription = objectWillChange.sink { events.append(self.saved.counter) }
    defer { subscription.cancel() }
    let snapshot = saved
    saved.counter = 1
    precondition(events == [0] && saved.counter == 1, "Notify before nested mutation")
    let binding = ObservedObject(wrappedValue: self).projectedValue.saved.counter
    binding.wrappedValue = 2
    precondition(events.last == 1 && saved.counter == 2, "ObservedObject member binding remains live")
    saved.contents["items"]?.append("new")
    precondition(snapshot.contents["items"] == ["old"] && snapshot.counter == 0, "Immutable queued snapshot")
    let cursor = AgentEventCursor(target: .init(value: "host/workspace/runtime"), threadId: "thread", epoch: "e1", sequence: 1)
    let first = events.count
    storeEventCursor(cursor, key: "key")
    precondition(events.count > first, "New cursor key publishes aliases/index changes")
    let before = events.count
    storeEventCursor(cursor, key: "key")
    precondition(events.count == before, "Identical cursor is a no-op")
    var next = cursor; next.epoch = "e2"; next.sequence = 3
    storeEventCursor(next, key: "key")
    precondition(events.count == before && saved.agentEventCursors?["key"] == next, "Cursor advances without UI invalidation")
    let durable = try JSONDecoder().decode(SavedSession.self, from: JSONEncoder().encode(saved))
    precondition(durable.agentEventCursors?["key"] == next, "Latest cursor remains in durable snapshot")
    next.threadId = "another-thread"
    storeEventCursor(next, key: "key")
    precondition(events.count > before, "Thread change publishes")
    var baseline = events.count; next.target = .init(value: "another-full-target")
    storeEventCursor(next, key: "key")
    precondition(events.count > baseline, "Full target change publishes")
    baseline = events.count; saved.counter = 3
    precondition(events.count == baseline + 1, "Metadata suppression never leaks")
    baseline = events.count
    confirmSelectedHistory(runtimePresent: false)
    precondition(events.count == baseline + 1 && loadedHistoryGeneration == historyGeneration, "First confirmation publishes even with identical cached values")
    baseline = events.count; confirmSelectedHistory(runtimePresent: false)
    precondition(events.count == baseline, "Repeated confirmation is a no-op")
    suggestionNeedsFreshHistory = true; confirmSelectedHistory(runtimePresent: true)
    precondition(events.count == baseline + 1 && !suggestionNeedsFreshHistory, "Fresh suggestion confirmation publishes")
    baseline = events.count; historyGeneration = UUID(); suggestionNeedsFreshHistory = true
    confirmSelectedHistory(runtimePresent: false)
    precondition(events.count == baseline + 1 && suggestionNeedsFreshHistory, "Missing runtime must not authorize suggestion freshness")
    baseline = events.count; storeConversationSyncIssue(nil, key: "key")
    precondition(events.count == baseline, "Absent sync issue stays a no-op")
    let issue = ConversationSyncIssue(message: "failure")
    storeConversationSyncIssue(issue, key: "key"); baseline = events.count
    storeConversationSyncIssue(issue, key: "key")
    precondition(events.count == baseline, "Identical visible error need not rebuild")
    var fresh = issue; fresh.historyFresh = true
    storeConversationSyncIssue(fresh, key: "key")
    precondition(events.count > baseline, "Freshness semantics are presentation input")
    storeConversationSyncIssue(nil, key: "key"); baseline = events.count
    storeConversationSyncIssue(nil, key: "key")
    precondition(events.count == baseline, "Repeated clear is a no-op")
    let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
    let values = try JSONSerialization.jsonObject(with: data) as! [String: Any]
    let runtime = try JSONDecoder().decode(ThreadRuntime.self, from: JSONSerialization.data(withJSONObject: values["fixture"]!))
    storeRuntime(runtime, key: "runtime"); baseline = events.count
    storeRuntime(runtime, key: "runtime")
    precondition(events.count == baseline, "Identical complete runtime stays a no-op")
    let vectors = values["vectors"] as! [[String: Any]]
    for vector in vectors {
      let changed = try JSONDecoder().decode(ThreadRuntime.self, from: JSONSerialization.data(withJSONObject: vector["value"]!))
      precondition(changed != runtime, "Runtime equality lost field: \\(vector["path"]!)")
    }
    var changed = runtime; changed.canControl = true
    storeRuntime(changed, key: "runtime")
    precondition(events.count > baseline, "Control change refreshes UI")
    print("PASS: saved publication, metadata durability, first confirmation, binding/snapshot boundaries; complete runtime equality \\(vectors.count) field mutations")
  }
}
@main struct Run { @MainActor static func main() throws { try PublicationHarness().verify() } }
`;
const file = join(directory, 'verify.swift'),
  binary = join(directory, 'verify');
await writeFile(file, swift, { mode: 0o600 });
for (const [command, args] of [
  ['xcrun', ['swiftc', '-O', '-swift-version', '5', '-parse-as-library', file, '-o', binary]],
  [binary, [join(directory, 'runtime-vectors.json')]],
]) {
  const result = spawnSync(command, args, { encoding: 'utf8', cwd: root });
  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  assert.equal(result.status, 0, command + ' failed');
}
