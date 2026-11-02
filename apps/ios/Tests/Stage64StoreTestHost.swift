#if !os(iOS) || !targetEnvironment(simulator)
#error("Stage64StoreTestHost is restricted to the dedicated iOS Simulator harness")
#endif

import SwiftUI

// Empty scene host only. Business sources are compiled into the test bundle.
@main
struct Stage64StoreTestHost: App {
  var body: some Scene {
    WindowGroup { Color.clear }
  }
}
