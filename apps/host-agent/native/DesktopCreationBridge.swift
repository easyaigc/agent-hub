import AppKit
import ApplicationServices
import CryptoKit
import Foundation

// Experimental local UI driver. Profiles require real acceptance for the installed build.
// No JavaScript injection, installation changes, history writes or extra Codex executor.
struct Selector: Decodable {
  let role: String
  let identifier: String?
  let labels: [String]?
}
struct Profile: Decodable {
  let desktopVersion: String
  let codexMode: Selector
  let composerRegion: Selector
  let composer: Selector
  let newChat: Selector
  let send: Selector
  let standalone: Selector
  let workspace: Selector
  let addFiles: Selector
  let chooseFiles: Selector
  let openPanel: Selector
  let pathField: Selector
  let go: Selector
  let selectedFile: Selector
  let open: Selector
  let attachedFileRole: String
  let threadMenu: Selector
  let copyMenu: Selector?
  let copyDeeplink: Selector
}
struct FileInput: Decodable {
  let id: String
  let name: String
  let path: String
  let sizeBytes: Int
  let sha256: String
}
struct Plan: Decodable {
  let hostId: String
  let clientOperationId: String
  let projectId: String?
  let directory: String?
  let text: String
  let files: [FileInput]
  let receiptPath: String
}
enum Failure: Error { case refused }
let appPath = "/Applications/ChatGPT.app"
var submitted = false
var activePlan: Plan?
if CommandLine.arguments == [CommandLine.arguments[0], "--status"] {
  let status: [String: Any] = [
    "accessibility": AXIsProcessTrusted(), "postEvents": CGPreflightPostEventAccess(),
    "desktopVersion": Bundle(path: appPath)?.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
  ]
  if let bytes = try? JSONSerialization.data(withJSONObject: status) { FileHandle.standardOutput.write(bytes) }
  exit(0)
}

func value(_ element: AXUIElement, _ attribute: String) -> CFTypeRef? {
  var result: CFTypeRef?
  guard AXUIElementCopyAttributeValue(element, attribute as CFString, &result) == .success else { return nil }
  return result
}
func text(_ element: AXUIElement, _ attribute: String) -> String {
  value(element, attribute) as? String ?? ""
}
func descendants(_ root: AXUIElement) -> [AXUIElement] {
  var queue = [root], index = 0
  while index < queue.count && queue.count < 12_000 {
    let item = queue[index]; index += 1
    queue.append(contentsOf: value(item, kAXChildrenAttribute) as? [AXUIElement] ?? [])
  }
  return queue
}
func matches(_ element: AXUIElement, _ selector: Selector) -> Bool {
  guard text(element, kAXRoleAttribute) == selector.role else { return false }
  if let identifier = selector.identifier, text(element, kAXIdentifierAttribute) != identifier { return false }
  if let labels = selector.labels {
    let values = [kAXTitleAttribute, kAXDescriptionAttribute, kAXHelpAttribute].map { text(element, $0) }
    guard labels.contains(where: values.contains) else { return false }
  }
  return selector.identifier != nil || selector.labels?.isEmpty == false
}
func one(_ root: AXUIElement, _ selector: Selector) throws -> AXUIElement {
  let found = descendants(root).filter { matches($0, selector) }
  guard found.count == 1 else { throw Failure.refused }
  return found[0]
}
func awaitOne(_ root: AXUIElement, _ selector: Selector, seconds: Double = 5) throws -> AXUIElement {
  let end = Date().addingTimeInterval(seconds)
  repeat {
    if let found = try? one(root, selector) { return found }
    RunLoop.current.run(until: Date().addingTimeInterval(0.08))
  } while Date() < end
  throw Failure.refused
}
func press(_ element: AXUIElement) throws {
  guard (value(element, kAXEnabledAttribute) as? Bool) != false,
    AXUIElementPerformAction(element, kAXPressAction as CFString) == .success
  else { throw Failure.refused }
}
func set(_ element: AXUIElement, _ content: String) throws {
  guard AXUIElementSetAttributeValue(element, kAXValueAttribute as CFString, content as CFString) == .success,
    text(element, kAXValueAttribute) == content else { throw Failure.refused }
}
func ensureFront(_ app: NSRunningApplication, _ window: AXUIElement, _ root: AXUIElement) throws {
  guard NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier,
    !app.isTerminated,
    let focused = value(root, kAXFocusedWindowAttribute), CFEqual(focused, window)
  else { throw Failure.refused }
}
func shortcut(_ key: CGKeyCode, _ flags: CGEventFlags) throws {
  guard let down = CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: true),
    let up = CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: false)
  else { throw Failure.refused }
  down.flags = flags; up.flags = flags
  down.post(tap: .cghidEventTap); up.post(tap: .cghidEventTap)
}
func result(_ phase: String, _ threadId: String? = nil) {
  var response: [String: Any] = ["phase": phase]
  if let plan = activePlan {
    response["hostId"] = plan.hostId
    response["clientOperationId"] = plan.clientOperationId
  }
  if let threadId { response["threadId"] = threadId }
  if let bytes = try? JSONSerialization.data(withJSONObject: response) {
    // Keep the exact copied ID if the parent dies before receiving stdout.
    if let plan = activePlan {
      let receipt = URL(fileURLWithPath: plan.receiptPath)
      if receipt.lastPathComponent == "native-result.json", receipt.deletingLastPathComponent().lastPathComponent == plan.clientOperationId {
        try? bytes.write(to: receipt, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: receipt.path)
      }
    }
    FileHandle.standardOutput.write(bytes)
  }
}

do {
  let input = FileHandle.standardInput.readDataToEndOfFile()
  guard input.count < 1_000_000 else { throw Failure.refused }
  let plan = try JSONDecoder().decode(Plan.self, from: input)
  activePlan = plan
  guard CommandLine.arguments.count == 3, CommandLine.arguments[1] == "--profile",
    AXIsProcessTrusted(), CGPreflightPostEventAccess() else { throw Failure.refused }
  let profile = try JSONDecoder().decode(Profile.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2])))
  guard plan.files.count <= 10, (plan.projectId == nil) == (plan.directory == nil),
    let bundle = Bundle(path: appPath), bundle.bundleIdentifier == "com.openai.codex",
    bundle.infoDictionary?["CFBundleShortVersionString"] as? String == profile.desktopVersion,
    let app = NSWorkspace.shared.runningApplications.first(where: {
      $0.bundleURL?.standardizedFileURL.path == appPath
    }), NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier
  else { throw Failure.refused }
  let root = AXUIElementCreateApplication(app.processIdentifier)
  guard let rawWindow = value(root, kAXFocusedWindowAttribute), CFGetTypeID(rawWindow) == AXUIElementGetTypeID() else { throw Failure.refused }
  let initialWindow = rawWindow as! AXUIElement
  _ = try one(initialWindow, profile.codexMode)
  // Refuse to navigate away from the user's unsent composer or an active modal.
  let previousRegion = try one(initialWindow, profile.composerRegion)
  let previous = try one(previousRegion, profile.composer)
  guard text(previous, kAXValueAttribute).isEmpty,
    !descendants(initialWindow).contains(where: { text($0, kAXRoleAttribute) == kAXSheetRole }),
    !descendants(previousRegion).contains(where: { text($0, kAXRoleAttribute) == profile.attachedFileRole })
  else { throw Failure.refused }
  for file in plan.files {
    let bytes = try Data(contentsOf: URL(fileURLWithPath: file.path))
    let digest = SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined()
    guard bytes.count == file.sizeBytes, digest == file.sha256,
      URL(fileURLWithPath: file.path).lastPathComponent == file.name else { throw Failure.refused }
  }
  if let directory = plan.directory {
    var isDirectory: ObjCBool = false
    guard directory.hasPrefix("/"), FileManager.default.fileExists(atPath: directory, isDirectory: &isDirectory), isDirectory.boolValue else { throw Failure.refused }
    var components = URLComponents()
    components.scheme = "codex"; components.host = "threads"; components.path = "/new"
    // Only the public path parameter. Do not rely on private projectId/mode or auto-submit.
    components.queryItems = [URLQueryItem(name: "path", value: directory)]
    guard let url = components.url else { throw Failure.refused }
    try ensureFront(app, initialWindow, root)
    var completed = false
    var openError: Error?
    NSWorkspace.shared.open([url], withApplicationAt: URL(fileURLWithPath: appPath), configuration: NSWorkspace.OpenConfiguration()) { _, error in
      openError = error; completed = true
    }
    let end = Date().addingTimeInterval(5)
    while !completed && Date() < end { RunLoop.current.run(until: Date().addingTimeInterval(0.08)) }
    guard completed, openError == nil else { throw Failure.refused }
  } else {
    try ensureFront(app, initialWindow, root)
    try press(try one(root, profile.standalone))
  }
  RunLoop.current.run(until: Date().addingTimeInterval(0.4))
  guard let currentWindow = value(root, kAXFocusedWindowAttribute), CFGetTypeID(currentWindow) == AXUIElementGetTypeID() else { throw Failure.refused }
  let window = currentWindow as! AXUIElement
  let region = try awaitOne(window, profile.composerRegion)
  _ = try one(window, profile.codexMode)
  _ = try one(window, profile.newChat)
  let composer = try awaitOne(region, profile.composer)
  guard text(composer, kAXValueAttribute).isEmpty else { throw Failure.refused }
  // Profile must expose the exact root or explicit standalone value. Basenames are ambiguous.
  let workspace = try one(window, profile.workspace)
  let expectedWorkspace = plan.directory ?? "projectless"
  guard [kAXValueAttribute, kAXHelpAttribute].contains(where: { text(workspace, $0) == expectedWorkspace }) else { throw Failure.refused }
  for file in plan.files {
    try ensureFront(app, window, root)
    try press(try one(window, profile.addFiles))
    try press(try awaitOne(root, profile.chooseFiles))
    let panel = try awaitOne(root, profile.openPanel)
    guard NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier else { throw Failure.refused }
    try shortcut(5, [.maskCommand, .maskShift]) // Native Open panel: Go to Folder.
    let field = try awaitOne(panel, profile.pathField)
    try set(field, file.path)
    try press(try one(panel, profile.go))
    let selectedFile = try awaitOne(panel, profile.selectedFile)
    guard [kAXValueAttribute, kAXTitleAttribute].contains(where: { text(selectedFile, $0) == file.name }) else { throw Failure.refused }
    try press(try one(panel, profile.open))
    let deadline = Date().addingTimeInterval(8)
    while !descendants(region).contains(where: { chip in
      text(chip, kAXRoleAttribute) == profile.attachedFileRole &&
        [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute].contains(where: { text(chip, $0) == file.name })
    }) && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.08)) }
  }
  try ensureFront(app, window, root)
  try set(composer, plan.text)
  let chips = descendants(region).filter { text($0, kAXRoleAttribute) == profile.attachedFileRole }
  guard chips.count == plan.files.count,
    plan.files.allSatisfy({ file in chips.contains { chip in
      [kAXTitleAttribute, kAXDescriptionAttribute, kAXValueAttribute].contains { text(chip, $0) == file.name }
    }}), text(composer, kAXValueAttribute) == plan.text else { throw Failure.refused }
  try ensureFront(app, window, root)
  _ = try one(window, profile.newChat)
  // Mark uncertain before the irreversible action, including a failed AXPress response.
  submitted = true
  try press(try one(window, profile.send))
  let clipboard = NSPasteboard.general
  let original = clipboard.pasteboardItems?.map { item in
    item.types.compactMap { type in item.data(forType: type).map { (type, $0) } }
  } ?? []
  let count = clipboard.changeCount
  try ensureFront(app, window, root)
  try press(try awaitOne(window, profile.threadMenu))
  if let copy = profile.copyMenu { try press(try awaitOne(root, copy)) }
  try press(try awaitOne(root, profile.copyDeeplink))
  let deadline = Date().addingTimeInterval(5)
  while clipboard.changeCount == count && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.08)) }
  let copiedCount = clipboard.changeCount
  let link = clipboard.string(forType: .string)
  guard copiedCount > count, let link, let url = URLComponents(string: link),
    url.scheme == "codex", url.host == "threads", url.queryItems?.contains(where: {
      $0.name == "hostId" && $0.value != "local"
    }) != true else { throw Failure.refused }
  let id = String(url.path.dropFirst())
  guard id.range(of: "^[a-zA-Z0-9_-]{8,150}$", options: .regularExpression) != nil else { throw Failure.refused }
  // Restore only if the user has not changed the clipboard since our own copy.
  if clipboard.changeCount == copiedCount {
    clipboard.clearContents()
    let restored = original.map { pairs in
      let item = NSPasteboardItem()
      for (type, data) in pairs { item.setData(data, forType: type) }
      return item
    }
    clipboard.writeObjects(restored)
  }
  result("submitted", id)
} catch {
  result(submitted ? "outcome-unknown" : "rejected-before-submit")
  exit(1)
}
