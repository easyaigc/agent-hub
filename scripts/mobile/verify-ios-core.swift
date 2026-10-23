import Foundation
import MobileCore

struct Scope: Decodable {
  let agentId: String, accountAuthority: String?, accountSubject: String?
  let hostId: String, workspaceId: String, runtimeId: String
  var core: TargetScope { TargetScope(agentId: agentId, accountAuthority: accountAuthority,
    accountSubject: accountSubject, hostId: hostId, workspaceId: workspaceId, runtimeId: runtimeId) }
}
struct IdentityInput: Decodable { let clientId: String, target: Scope?, threadId: String?, projectId: String? }
struct IdentityCase: Decodable { let name: String, input: IdentityInput, expected: String }
struct Message: Decodable { let role: String, status: String? }
struct ComposerValues: Decodable {
  let stopping: Bool, running: Bool, sending: Bool, creationPending: Bool
  let text: String, attachmentCount: Int32, canSend: Bool, canStop: Bool, canRunAgain: Bool
  let runtimeState: String?, lastReceiptState: String?, messages: [Message]
  var core: ComposerInput { ComposerInput(stopping: stopping, running: running, sending: sending,
    creationPending: creationPending, text: text, attachmentCount: attachmentCount,
    canSend: canSend, canStop: canStop, canRunAgain: canRunAgain, runtimeState: runtimeState,
    lastReceiptState: lastReceiptState, messages: messages.map { ComposerMessage(role: $0.role, status: $0.status) }) }
}
struct Decision: Decodable { let action: String, enabled: Bool }
struct ComposerCase: Decodable { let name: String, input: ComposerValues, expected: Decision }
struct ContentCase: Decodable { let text: String, count: Int32, expected: Bool }
struct Vectors: Decodable { let identities: [IdentityCase], composer: [ComposerCase], content: [ContentCase] }
let vectors = try JSONDecoder().decode(Vectors.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
for test in vectors.identities {
  let value = test.input
  let actual = MobileRules.shared.cacheKey(clientId: value.clientId, target: value.target?.core,
    threadId: value.threadId, projectId: value.projectId)
  precondition(actual == test.expected, test.name)
}
for test in vectors.composer {
  let actual = MobileRules.shared.composer(input: test.input.core)
  precondition(actual.action == test.expected.action && actual.enabled == test.expected.enabled, test.name)
}
for test in vectors.content {
  precondition(MobileRules.shared.hasContent(text: test.text, attachmentCount: test.count) == test.expected)
  precondition((!test.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || test.count > 0) == test.expected)
}
// Exercise native value-object ownership repeatedly within autorelease pools.
// There is no subscription/callback retained by the synchronous core facade.
for _ in 0..<1_000 { autoreleasepool { _ = MobileRules.shared.composer(input: vectors.composer[0].input.core) } }
print("Swift/Kotlin Simulator boundary passed: \(vectors.identities.count) identity, \(vectors.composer.count) composer, \(vectors.content.count) content vectors")
