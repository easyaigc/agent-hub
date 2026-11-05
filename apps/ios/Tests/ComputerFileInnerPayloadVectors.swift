import CryptoKit
import Foundation

// UNEXECUTED fixture oracle only. This deliberately bounds tiny vector Data;
// it is not an iOS large-file codec, encrypted envelope or product/A6 wiring.
enum ComputerFileInnerPayloadVectors {
  private enum Invalid: Error { case fixture }
  private static func require(_ value: Bool) throws { guard value else { throw Invalid.fixture } }
  private struct Fixture: Decodable { let schema: String; let vectors: [Vector] }
  private struct Entry: Decodable { let generation: String?; let kind: String; let name: String; let size: Int; let sha256: String; let bodyHex: String }
  private struct Receipt: Decodable { let recordCount: Int; let payloadBytes: Int64; let manifestBytes: Int64; let fileBytes: Int64; let manifestSha256: String; let payloadSha256: String; let fileSha256: String }
  private struct Vector: Decodable { let id: String; let entries: [Entry]; let wireHex: String; let expected: Receipt }
  private static func hex(_ value: some Sequence<UInt8>) -> String { value.map { String(format: "%02x", $0) }.joined() }
  private static func hash(_ bytes: [UInt8]) -> String { hex(SHA256.hash(data: Data(bytes))) }
  private static func unhex(_ value: String) throws -> [UInt8] {
    let text = Array(value.utf8); try require(text.count % 2 == 0 && text.count <= 65_536)
    func nibble(_ byte: UInt8) throws -> UInt8 {
      if byte >= 48 && byte <= 57 { return byte - 48 }
      if byte >= 97 && byte <= 102 { return byte - 87 }
      throw Invalid.fixture
    }
    var bytes: [UInt8] = []
    for index in stride(from: 0, to: text.count, by: 2) {
      let high = try nibble(text[index]), low = try nibble(text[index + 1]); bytes.append((high << 4) | low)
    }
    return bytes
  }
  private struct Cursor {
    let bytes: [UInt8]; var offset = 0
    mutating func take(_ size: Int) throws -> [UInt8] {
      try ComputerFileInnerPayloadVectors.require(size >= 0 && size <= bytes.count - offset)
      let result = Array(bytes[offset ..< offset + size]); offset += size; return result
    }
    mutating func unsigned(_ size: Int) throws -> UInt64 {
      try ComputerFileInnerPayloadVectors.require(size >= 1 && size <= 8)
      return try take(size).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }
  }
  private static func generation(_ bytes: [UInt8]) throws -> String {
    try require(bytes.count == 16)
    let text = Array(hex(bytes))
    return String(text[0..<8]) + "-" + String(text[8..<12]) + "-" + String(text[12..<16]) + "-" + String(text[16..<20]) + "-" + String(text[20..<32])
  }
  private static func verify(_ vector: Vector, wire: [UInt8]) throws {
    var input = Cursor(bytes: wire)
    try require(try input.take(8) == Array("AHF59I1\n".utf8))
    try require(try input.unsigned(4) == 1)
    try require(try input.unsigned(2) == 30)
    try require(try input.take(30) == Array("agent-hub/f59-inner-payload/v1".utf8))
    try require(vector.entries.count >= 1 && vector.entries.count <= 8)
    try require(try input.unsigned(4) == UInt64(vector.entries.count))
    try require(vector.expected.payloadBytes >= 1 && vector.expected.payloadBytes <= 1024)
    try require(try input.unsigned(8) == UInt64(vector.expected.payloadBytes))
    for expected in vector.entries {
      let flag = try input.unsigned(1); try require(flag <= 1)
      let observed: String?
      if flag == 0 { observed = nil } else { observed = try generation(input.take(16)) }
      try require(observed == expected.generation)
      let kind = try input.unsigned(1); try require(kind == 1 || kind == 2)
      try require((kind == 1 ? "journal" : "chunk") == expected.kind)
      let length = try input.unsigned(2); try require(length >= 1 && length <= 128)
      let rawName = try input.take(Int(length)); try require(rawName.allSatisfy { $0 >= 1 && $0 <= 127 })
      try require(String(bytes: rawName, encoding: .ascii) == expected.name)
      try require(expected.size >= 1 && expected.size <= 1024)
      try require(try input.unsigned(8) == UInt64(expected.size))
      try require(try hex(input.take(32)) == expected.sha256)
    }
    try require(Int64(input.offset) == vector.expected.manifestBytes)
    let manifestHash = hash(Array(wire[..<input.offset]))
    try require(manifestHash == vector.expected.manifestSha256)
    var payload = SHA256(); var payloadBytes: Int64 = 0
    for (ordinal, expected) in vector.entries.enumerated() {
      try require(try input.unsigned(8) == UInt64(ordinal))
      try require(try input.unsigned(8) == UInt64(expected.size))
      let bytes = try input.take(expected.size)
      let expectedBytes = try unhex(expected.bodyHex); try require(bytes == expectedBytes)
      try require(hash(bytes) == expected.sha256)
      payload.update(data: Data(bytes)); payloadBytes += Int64(bytes.count)
    }
    let payloadHash = hex(payload.finalize())
    try require(payloadBytes == vector.expected.payloadBytes && payloadHash == vector.expected.payloadSha256)
    try require(try input.take(8) == Array("AHF59END".utf8))
    try require(try input.unsigned(4) == UInt64(vector.expected.recordCount))
    try require(try input.unsigned(8) == UInt64(payloadBytes))
    try require(try hex(input.take(32)) == manifestHash)
    try require(try hex(input.take(32)) == payloadHash)
    try require(input.offset == wire.count && Int64(wire.count) == vector.expected.fileBytes)
    try require(hash(wire) == vector.expected.fileSha256)
  }
  static func run(fixtureData: Data) throws {
    try require(fixtureData.count <= 65_536)
    let fixture = try JSONDecoder().decode(Fixture.self, from: fixtureData)
    try require(fixture.schema == "agent-hub/f59-inner-payload-vectors/v1" && fixture.vectors.count == 3)
    for vector in fixture.vectors {
      let bytes = try unhex(vector.wireHex); try verify(vector, wire: bytes)
      var refusedTrailing = false
      do { try verify(vector, wire: bytes + [0]) } catch { refusedTrailing = true }
      try require(refusedTrailing)
      var refusedMissing = false
      do { try verify(vector, wire: Array(bytes.dropLast())) } catch { refusedMissing = true }
      try require(refusedMissing)
    }
  }
}
