import Foundation

/// Phone-local navigation history. A scope identifies one Agent/Host/workspace/runtime.
struct RecentProjects: Codable {
  static let limit = 5
  var projectIDsByScope: [String: [String]] = [:]

  mutating func record(_ projectID: String, in scope: String) {
    var ids = projectIDsByScope[scope, default: []]
    ids.removeAll { $0 == projectID }
    ids.insert(projectID, at: 0)
    projectIDsByScope[scope] = Array(ids.prefix(Self.limit))
  }

  func recentIDs(in scope: String, availableIDs: Set<String>) -> [String] {
    var seen = Set<String>()
    return Array(projectIDsByScope[scope, default: []].filter {
      availableIDs.contains($0) && seen.insert($0).inserted
    }.prefix(Self.limit))
  }
}
