import Foundation

/// A space is a named group of vault files. `files` are vault-relative paths; order is display order.
/// Deleting a space never touches vault files.
public struct Space: Hashable, Codable, Sendable {
    public var id: String
    public var name: String
    public var files: [String]
    public var defaultFile: String?

    public init(id: String, name: String, files: [String] = [], defaultFile: String? = nil) {
        self.id = id; self.name = name; self.files = files; self.defaultFile = defaultFile
    }

    /// File a new task is written to unless the user picks another: default, else first.
    public var creationFile: String? {
        if let d = defaultFile, files.contains(d) { return d }
        return files.first
    }
}

/// All spaces plus the active one. Immutable in use: every operation returns a new value, unknown ids are no-ops
/// (the result equals the receiver).
public struct SpacesData: Hashable, Codable, Sendable {
    public var spaces: [Space]
    public var activeId: String?

    public init(spaces: [Space] = [], activeId: String? = nil) { self.spaces = spaces; self.activeId = activeId }

    /// The active space; falls back to the first if `activeId` is unset or dangling.
    public var active: Space? { spaces.first(where: { $0.id == activeId }) ?? spaces.first }

    /// Union of all files that belong to any space, first-seen order. Only these are scanned.
    public var assignedFiles: [String] {
        var seen = Set<String>()
        return spaces.flatMap(\.files).filter { seen.insert($0).inserted }
    }

    public func add(id: String, name: String) -> SpacesData {
        let n = name.ktTrimmed
        if n.isEmpty || spaces.contains(where: { $0.id == id }) { return self }
        return SpacesData(spaces: spaces + [Space(id: id, name: n)], activeId: id)
    }

    public func rename(id: String, name: String) -> SpacesData {
        let n = name.ktTrimmed
        if n.isEmpty { return self }
        return mapSpace(id) { var s = $0; s.name = n; return s }
    }

    public func delete(id: String) -> SpacesData {
        let rest = spaces.filter { $0.id != id }
        if rest.count == spaces.count { return self }
        return SpacesData(spaces: rest, activeId: activeId == id ? rest.first?.id : activeId)
    }

    /// Moves a space by `delta` positions (negative = up), clamped to the list bounds.
    public func move(id: String, delta: Int) -> SpacesData {
        guard let from = spaces.firstIndex(where: { $0.id == id }) else { return self }
        let to = min(max(from + delta, 0), spaces.count - 1)
        if to == from { return self }
        var list = spaces
        list.insert(list.remove(at: from), at: to)
        return SpacesData(spaces: list, activeId: activeId)
    }

    /// Replaces the file list (deduplicated, order kept). The default file is dropped if it left the list.
    public func setFiles(id: String, files: [String]) -> SpacesData {
        mapSpace(id) { s in
            var seen = Set<String>()
            let f = files.filter { seen.insert($0).inserted }
            var out = s
            out.files = f
            out.defaultFile = s.defaultFile.flatMap { f.contains($0) ? $0 : nil }
            return out
        }
    }

    /// `file` must belong to the space; anything else clears the default.
    public func setDefault(id: String, file: String?) -> SpacesData {
        mapSpace(id) { s in
            var out = s
            out.defaultFile = file.flatMap { s.files.contains($0) ? $0 : nil }
            return out
        }
    }

    public func setActive(id: String) -> SpacesData {
        spaces.contains(where: { $0.id == id }) ? SpacesData(spaces: spaces, activeId: id) : self
    }

    private func mapSpace(_ id: String, _ f: (Space) -> Space) -> SpacesData {
        if !spaces.contains(where: { $0.id == id }) { return self }
        return SpacesData(spaces: spaces.map { $0.id == id ? f($0) : $0 }, activeId: activeId)
    }
}
