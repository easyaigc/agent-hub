import Foundation

@main struct RecentProjectsRules {
  static func main() throws {
    var recent = RecentProjects()
    for id in ["a", "b", "c", "d", "e", "f", "c"] { recent.record(id, in: "host-a") }
    recent.record("z", in: "host-b")
    let available: Set<String> = ["a", "b", "c", "d", "e", "f", "z"]
    precondition(recent.recentIDs(in: "host-a", availableIDs: available) == ["c", "f", "e", "d", "b"])
    precondition(recent.recentIDs(in: "host-b", availableIDs: available) == ["z"])
    precondition(recent.recentIDs(in: "host-a", availableIDs: ["c", "e"]) == ["c", "e"])
    precondition(recent.recentIDs(in: "host-a", availableIDs: []).isEmpty)
    // Display names are intentionally absent from the stored history: a rename
    // with the same project ID keeps the same recent entry.
    precondition(recent.recentIDs(in: "host-a", availableIDs: ["c"]) == ["c"])
    let data = try JSONEncoder().encode(recent)
    let decoded = try JSONDecoder().decode(RecentProjects.self, from: data)
    precondition(decoded.recentIDs(in: "host-a", availableIDs: available) == ["c", "f", "e", "d", "b"])
    print("RecentProjects rules passed")
  }
}
