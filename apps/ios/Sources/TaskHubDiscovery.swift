import Foundation
import Combine

@MainActor final class TaskHubDiscovery: NSObject, ObservableObject, @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
  struct Candidate: Identifiable {
    var id: String
    var name: String
    var endpoint: String
    var binding: TaskHubBinding?
    var error: String?
  }
  @Published var candidates: [Candidate] = []
  @Published var searching = false
  @Published private(set) var error: String?
  private let browser = NetServiceBrowser()
  private var services: [NetService] = []
  private var probes: [String: Task<Void, Never>] = [:]
  private var advertisements: [String: Candidate] = [:]
  private func key(_ service: NetService) -> String { service.domain + service.type + service.name }
  private func publish() {
    var seen = Set<String>()
    candidates = advertisements.values.sorted { $0.id < $1.id }.filter { value in
      let identity = value.binding.map { $0.service.identity + "/" + $0.fingerprint } ?? value.id
      return seen.insert(identity).inserted
    }
  }
  func start() {
    guard !searching else { return }; candidates.removeAll(); advertisements.removeAll(); error = nil; searching = true
    browser.delegate = self; browser.schedule(in: .main, forMode: .common)
    browser.searchForServices(ofType: "_agenthub-task._tcp.", inDomain: "local.")
  }
  func stop() {
    searching = false; browser.stop(); services.forEach { $0.stop() }; services.removeAll()
    probes.values.forEach { $0.cancel() }; probes.removeAll(); candidates.removeAll(); advertisements.removeAll()
  }
  func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
    guard searching else { return }
    services.append(service); service.delegate = self; service.resolve(withTimeout: 5)
  }
  func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
    let id = key(service)
    probes[id]?.cancel(); probes.removeValue(forKey: id)
    advertisements.removeValue(forKey: id); publish(); services.removeAll { $0 == service }
  }
  func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
    searching = false
    error = L10n.tr("局域网发现暂不可用（\(errorDict[NetService.errorCode]?.stringValue ?? L10n.tr("未知"))）。请核对网络和 App 的本地网络权限，也可使用主电脑的配对 JSON。")
  }
  func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
    guard searching else { return }
    let id = key(sender)
    advertisements[id] = Candidate(id: id, name: sender.name, endpoint: "", error: L10n.tr("服务地址解析失败，请重新发现或使用配对 JSON。"))
    publish()
  }
  func netServiceDidResolveAddress(_ sender: NetService) {
    guard searching, let host = sender.hostName, sender.port > 0 else { return }
    let endpoint = "https://\(host.trimmingCharacters(in: CharacterSet(charactersIn: "."))):\(sender.port)"
    let id = key(sender)
    advertisements[id] = Candidate(id: id, name: sender.name, endpoint: endpoint); publish()
    probes[id]?.cancel()
    probes[id] = Task { [weak self] in
      do {
        let binding = try await TaskHubClient.probe(endpoint: endpoint)
        guard !Task.isCancelled, let self, self.searching, self.advertisements[id] != nil else { return }
        self.advertisements[id]?.binding = binding; self.advertisements[id]?.name = binding.service.name; self.publish()
      } catch {
        guard !Task.isCancelled, let self, self.advertisements[id] != nil else { return }
        self.advertisements[id]?.error = error.localizedDescription; self.publish()
      }
    }
  }
}
