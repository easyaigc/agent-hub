// Generated from packages/protocol/fixtures/mobile/reading-policy.json; edit the definition, then regenerate.
enum SharedReadingPolicy {
  static func nextRevision(previousRevision: Int64, sameVersion: Bool) -> Int64 { (sameVersion ? previousRevision : (previousRevision + 1)) }
  static func reconciledUnread(previousUnread: Bool, hadLatest: Bool, latestAccepted: Bool, latestChanged: Bool, sourceUnread: Bool, readSettles: Bool) -> Bool { (readSettles ? false : (latestAccepted ? (hadLatest ? (latestChanged || previousUnread) : (previousUnread || sourceUnread)) : previousUnread)) }
  static func newPushUnread(readSame: Bool, readRevision: Int64, latestRevision: Int64) -> Bool { ((!readSame) && (readRevision < latestRevision)) }
  static func preserveHistoryPrefix(windowed: Bool, hasPaged: Bool, sameGeneration: Bool, sameRevision: Bool) -> Bool { ((windowed && hasPaged) && (sameGeneration && sameRevision)) }
}
