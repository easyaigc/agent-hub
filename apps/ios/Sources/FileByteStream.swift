import Foundation
import CryptoKit
import Darwin

/// File byte budgets are separate from image/Markdown rendering budgets.
/// The safe-integer ceiling is the common JSON wire range, not an allocation size.
enum FileByteStream {
  enum Failure: LocalizedError {
    case invalidFile
    var errorDescription: String? { L10n.tr("文件为空、已变化或超过当前文件限制。") }
  }
  static let maximumWireBytes = 9_007_199_254_740_991
  static let chunkBytes = 192 * 1024
  static func validate(size: Int, maximum: Int?, allowEmpty: Bool = false) throws {
    guard size >= (allowEmpty ? 0 : 1), size <= maximumWireBytes,
      maximum.map({ $0 > 0 && size <= $0 }) ?? true else { throw Failure.invalidFile }
  }
  static func digest(_ value: SHA256.Digest) -> String { value.map { String(format: "%02x", $0) }.joined() }

  /// Keeps one descriptor while hashing. A path replacement or
  /// in-place edit fails before completion, including same-size edits.
  final class Reader {
    private let handle: FileHandle
    private let original: stat
    let url: URL
    let size: Int
    init(_ url: URL, maximum: Int? = nil, allowEmpty: Bool = false) throws {
      guard url.isFileURL else { throw Failure.invalidFile }
      let fd = Darwin.open(url.path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK)
      guard fd >= 0 else { throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO) }
      handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
      var info = stat()
      guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG,
        info.st_size >= 0, info.st_size <= maximumWireBytes else {
        try? handle.close(); throw Failure.invalidFile
      }
      original = info; self.url = url; size = Int(info.st_size)
      do { try validate(size: size, maximum: maximum, allowEmpty: allowEmpty) }
      catch { try? handle.close(); throw error }
    }
    deinit { try? handle.close() }
    func unchanged() throws {
      var now = stat(), path = stat()
      guard fstat(handle.fileDescriptor, &now) == 0, lstat(url.path, &path) == 0,
        now.st_dev == original.st_dev, now.st_ino == original.st_ino,
        path.st_dev == original.st_dev, path.st_ino == original.st_ino,
        path.st_mode & S_IFMT == S_IFREG, now.st_size == original.st_size,
        now.st_mtimespec.tv_sec == original.st_mtimespec.tv_sec,
        now.st_mtimespec.tv_nsec == original.st_mtimespec.tv_nsec,
        now.st_ctimespec.tv_sec == original.st_ctimespec.tv_sec,
        now.st_ctimespec.tv_nsec == original.st_ctimespec.tv_nsec else { throw Failure.invalidFile }
    }
    func read(offset: Int, length: Int = chunkBytes) throws -> Data {
      try Task.checkCancellation(); try unchanged()
      guard offset >= 0, offset <= size, length > 0, length <= chunkBytes else { throw Failure.invalidFile }
      let count = min(length, size - offset)
      try handle.seek(toOffset: UInt64(offset))
      var data = Data()
      while data.count < count {
        guard let part = try handle.read(upToCount: count - data.count), !part.isEmpty else { throw Failure.invalidFile }
        data.append(part)
      }
      try unchanged(); return data
    }
    func sha256() throws -> String {
      var hash = SHA256(), offset = 0
      while offset < size { let data = try read(offset: offset); hash.update(data: data); offset += data.count }
      try unchanged(); return digest(hash.finalize())
    }
  }

}
