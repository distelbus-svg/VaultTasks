import Foundation

public struct WebDavConfig: Equatable, Sendable {
    public var url: String
    public var user: String
    public var password: String
    public var baseFolder: String
    public init(url: String, user: String, password: String, baseFolder: String) {
        self.url = url; self.user = user; self.password = password; self.baseFolder = baseFolder
    }
}

/// A failure with a message meant for the user (shown verbatim under the sync form and in reports).
public struct WebDavError: Error, LocalizedError, Equatable, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

public struct HTTPRequest: Sendable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: Data?
    public init(method: String, url: URL, headers: [String: String] = [:], body: Data? = nil) {
        self.method = method; self.url = url; self.headers = headers; self.body = body
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    /// Header names lowercased.
    public var headers: [String: String]
    public var body: Data
    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { a, _ in a })
        self.body = body
    }
    public func header(_ name: String) -> String? { headers[name.lowercased()] }
}

/// HTTP seam. Implementations must NOT follow redirects: they return the 3xx response, and the client reports it
/// (a followed redirect would turn a PUT into a GET and silently lose the upload).
public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// Minimal WebDAV client (spec §8): PROPFIND depth 0, GET, conditional PUT, MKCOL. Port of Android's `WebDavClient`.
public final class WebDavClient: RemoteStore, @unchecked Sendable {
    private let transport: any HTTPTransport
    private let auth: String
    private let root: String
    private let baseSegments: [String]
    private let lock = NSLock()
    private var knownDirs: Set<[String]> = []

    public init(config: WebDavConfig, transport: any HTTPTransport) throws {
        var r = config.url.ktTrimmed
        while r.hasSuffix("/") { r.removeLast() }
        guard let u = URL(string: r), let scheme = u.scheme?.lowercased(), scheme == "http" || scheme == "https", u.host() != nil else {
            throw WebDavError("The server URL must start with http:// or https://")
        }
        self.transport = transport
        root = r
        auth = "Basic " + Data("\(config.user):\(config.password)".utf8).base64EncodedString()
        baseSegments = config.baseFolder.split(separator: "/").map(String.init).filter { !$0.ktTrimmed.isEmpty }
    }

    // ---- URLs and requests --------------------------------------------------------------------------------

    private static let pathSafe = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@")

    static func encode(_ segment: String) -> String { segment.addingPercentEncoding(withAllowedCharacters: pathSafe) ?? segment }

    /// `collection` adds the trailing slash servers expect on directories (they redirect without it).
    private func rawURL(_ segments: [String], collection: Bool) throws -> URL {
        var s = root
        for seg in segments { s += "/" + Self.encode(seg) }
        if collection { s += "/" }
        guard let u = URL(string: s) else { throw WebDavError("The server URL is not valid") }
        return u
    }

    func url(_ segments: [String], collection: Bool = false) throws -> URL { try rawURL(baseSegments + segments, collection: collection) }

    private func request(_ method: String, _ segments: [String], collection: Bool = false) throws -> HTTPRequest {
        HTTPRequest(method: method, url: try url(segments, collection: collection), headers: ["Authorization": auth])
    }

    private func check(_ r: HTTPResponse, allowed: Set<Int> = []) throws {
        if (200..<300).contains(r.status) || allowed.contains(r.status) { return }
        switch r.status {
        case 401, 403: throw WebDavError("Not authorized (HTTP \(r.status)), check user and password")
        case 301, 302, 303, 307, 308: throw WebDavError("Server redirected (HTTP \(r.status)), use the final URL")
        default: throw WebDavError("Server answered HTTP \(r.status)")
        }
    }

    // ---- PROPFIND -----------------------------------------------------------------------------------------

    private static let propfindBody =
        #"<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getlastmodified/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>"#

    private func propfind(_ segments: [String], collection: Bool = false) async throws -> PropfindParser.Entry? {
        var req = try request("PROPFIND", segments, collection: collection)
        req.headers["Depth"] = "0"
        req.headers["Content-Type"] = "application/xml"
        req.body = Data(Self.propfindBody.utf8)
        let r = try await transport.send(req)
        if r.status == 404 { return nil }
        try check(r)
        do { return try PropfindParser.parse(String(decoding: r.body, as: UTF8.self)).first }
        catch { throw WebDavError("The server's answer could not be read (not WebDAV?)") }
    }

    private static func synthetic(_ lastModified: String?, _ size: Int64?) -> String { "lm|\(lastModified ?? "")|\(size ?? -1)" }
    private static func token(_ e: PropfindParser.Entry) -> String { e.etag ?? synthetic(e.lastModified, e.size) }

    /// RFC 1123 HTTP date to epoch millis; nil if absent or unreadable.
    static func millis(_ httpDate: String?) -> Int64? {
        guard let s = httpDate?.ktTrimmed, !s.isEmpty else { return nil }
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, d MMM yyyy HH:mm:ss zzz"
        return f.date(from: s).map { Int64(($0.timeIntervalSince1970 * 1000).rounded()) }
    }

    /// Connection check on the base folder. Returns a human-readable result; throws on failure.
    public func test() async throws -> String {
        guard let e = try await propfind([], collection: true) else {
            return "Connected. The folder doesn't exist yet and will be created on the first push."
        }
        return e.isCollection ? "Connected." : "The remote folder path points to a file."
    }

    // ---- RemoteStore --------------------------------------------------------------------------------------

    private static func segments(_ path: String) -> [String] { path.split(separator: "/").map(String.init) }

    public func stat(_ path: String) async throws -> RemoteStat? {
        guard let e = try await propfind(Self.segments(path)), !e.isCollection else { return nil }
        return RemoteStat(token: Self.token(e), modified: Self.millis(e.lastModified))
    }

    public func get(_ path: String) async throws -> RemoteData? {
        let segs = Self.segments(path)
        // Token first, content second: if the file changes in between, the recorded token is older than the content,
        // which only makes the next check report "changed on server" (the safe direction).
        guard let entry = try await propfind(segs), !entry.isCollection else { return nil }
        let r = try await transport.send(try request("GET", segs))
        if r.status == 404 { return nil }
        try check(r)
        return RemoteData(bytes: r.body, token: Self.token(entry), modified: Self.millis(entry.lastModified))
    }

    public func put(_ path: String, _ bytes: Data, expectedToken: String?) async throws -> PutResult {
        let segs = Self.segments(path)
        var req = try request("PUT", segs)
        req.body = bytes
        req.headers["Content-Type"] = "text/markdown"
        if let expected = expectedToken {
            if expected.hasPrefix("\"") {
                req.headers["If-Match"] = expected // strong ETag
            } else if try await stat(path)?.token != expected {
                // No usable ETag on this server: check by hand (small race, unavoidable).
                return .preconditionFailed
            }
        } else {
            req.headers["If-None-Match"] = "*"
        }
        try await ensureDirs(Array(segs.dropLast()))
        let r = try await transport.send(req)
        if r.status == 412 { return .preconditionFailed }
        try check(r)
        if let etag = r.header("ETag"), etag.hasPrefix("\"") { return .ok(token: etag) }
        guard let now = try await stat(path) else { return .failed("Uploaded, but the file is not visible on the server") }
        return .ok(token: now.token)
    }

    /// MKCOL the base folder and every directory below it that the file needs (405 = already exists).
    private func ensureDirs(_ dirs: [String]) async throws {
        let all = baseSegments + dirs
        for i in all.indices { try await mkcol(Array(all.prefix(i + 1))) }
    }

    private func mkcol(_ segments: [String]) async throws {
        if lock.withLock({ knownDirs.contains(segments) }) { return }
        let req = HTTPRequest(method: "MKCOL", url: try rawURL(segments, collection: true), headers: ["Authorization": auth])
        try check(try await transport.send(req), allowed: [405])
        lock.withLock { _ = knownDirs.insert(segments) }
    }
}
