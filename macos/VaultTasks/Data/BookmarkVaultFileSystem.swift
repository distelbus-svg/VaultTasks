import Foundation
import VaultTasksCore

/// `VaultFileSystem` over real paths inside the security-scoped vault folder (access is held by `VaultAccess`).
struct BookmarkVaultFileSystem: VaultFileSystem {
    let root: URL

    struct BadPath: Error { let path: String }

    private func url(_ path: String) throws -> URL {
        let parts = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !path.isEmpty, !path.hasPrefix("/"), !parts.contains(where: { $0 == ".." || $0.isEmpty }) else { throw BadPath(path: path) }
        return root.appending(path: path, directoryHint: .notDirectory)
    }

    private func info(_ path: String, _ u: URL) throws -> VaultFileInfo? {
        do {
            let a = try FileManager.default.attributesOfItem(atPath: u.path)
            let mtime = (a[.modificationDate] as? Date).map { Int64($0.timeIntervalSince1970 * 1000) } ?? 0
            return VaultFileInfo(path: path, lastModified: mtime, size: (a[.size] as? NSNumber)?.int64Value ?? 0)
        } catch let e as CocoaError where e.code == .fileReadNoSuchFile || e.code == .fileNoSuchFile {
            return nil
        }
    }

    /// Every `.md` file, skipping dot-files and dot-directories (`.obsidian`, `.git`, `.trash`) and package contents.
    func listMarkdown() throws -> [VaultFileInfo] {
        let fm = FileManager.default
        let base = root.path.hasSuffix("/") ? root.path : root.path + "/"
        guard let e = fm.enumerator(at: root, includingPropertiesForKeys: [.isRegularFileKey, .contentModificationDateKey, .fileSizeKey],
                                    options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { return [] }
        var out: [VaultFileInfo] = []
        for case let u as URL in e {
            guard u.pathExtension == "md", u.path.hasPrefix(base) else { continue }
            let v = try u.resourceValues(forKeys: [.isRegularFileKey, .contentModificationDateKey, .fileSizeKey])
            guard v.isRegularFile == true else { continue }
            out.append(VaultFileInfo(path: String(u.path.dropFirst(base.count)),
                                     lastModified: Int64((v.contentModificationDate ?? .distantPast).timeIntervalSince1970 * 1000),
                                     size: Int64(v.fileSize ?? 0)))
        }
        return out.sorted { utf16Less($0.path, $1.path) }
    }

    func stat(_ path: String) throws -> VaultFileInfo? { try info(path, url(path)) }

    func read(_ path: String) throws -> Data? {
        do { return try Data(contentsOf: url(path)) }
        catch let e as CocoaError where e.code == .fileReadNoSuchFile || e.code == .fileNoSuchFile { return nil }
    }

    /// Atomic replace: a temp file in the same directory, then swap. Obsidian, Git and sync tools read the vault
    /// concurrently and must never see a half-written file. The target must exist (we only edit, never create).
    func write(_ path: String, _ bytes: Data) throws {
        let target = try url(path)
        guard FileManager.default.fileExists(atPath: target.path) else { throw CocoaError(.fileNoSuchFile) }
        let tmp = target.deletingLastPathComponent().appending(path: ".vt-\(UUID().uuidString).tmp", directoryHint: .notDirectory)
        do {
            try bytes.write(to: tmp)
            _ = try FileManager.default.replaceItemAt(target, withItemAt: tmp)
        } catch {
            try? FileManager.default.removeItem(at: tmp)
            throw error
        }
    }
}
