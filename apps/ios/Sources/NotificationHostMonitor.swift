import Foundation

/// Each target owns its wait and retry clock. A disconnected transport must
/// never prevent another computer from registering or reading its notices.
@MainActor final class NotificationHostMonitor {
  private var tasks: [String: Task<Void, Never>] = [:]
  private let interval: Duration
  init(interval: Duration = .seconds(3)) { self.interval = interval }

  func reconcile(_ ids: Set<String>, poll: @escaping @MainActor (String) async -> Void) {
    for id in Set(tasks.keys).subtracting(ids) { tasks.removeValue(forKey: id)?.cancel() }
    for id in ids where tasks[id] == nil {
      tasks[id] = Task { [interval] in
        while !Task.isCancelled {
          await poll(id)
          if Task.isCancelled { return }
          do { try await Task.sleep(for: interval) } catch { return }
        }
      }
    }
  }
  func cancel() {
    for task in tasks.values { task.cancel() }
    tasks.removeAll()
  }
}
