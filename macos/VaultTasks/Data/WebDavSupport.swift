import Foundation
import Security
import VaultTasksCore

/// `URLSession` transport for `WebDavClient` (§8). Redirects are refused: the delegate returns nil, so the 3xx answer
/// reaches the client, which reports "use the final URL" (a followed redirect would turn a PUT into a GET).
///
/// Timeouts: URLSession has no separate connect timeout, so the request's idle timeout covers connecting and reading
/// (30 s), uploads get 60 s.
final class URLSessionTransport: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
    private lazy var session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.urlCache = nil
        c.requestCachePolicy = .reloadIgnoringLocalCacheData
        c.timeoutIntervalForResource = 300
        return URLSession(configuration: c, delegate: self, delegateQueue: nil)
    }()

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? { nil }

    func send(_ r: HTTPRequest) async throws -> HTTPResponse {
        var req = URLRequest(url: r.url)
        req.httpMethod = r.method
        req.timeoutInterval = r.method == "PUT" ? 60 : 30
        for (k, v) in r.headers { req.setValue(v, forHTTPHeaderField: k) }
        req.httpBody = r.body
        do {
            let (data, response) = try await session.data(for: req)
            guard let http = response as? HTTPURLResponse else { throw WebDavError("The server did not answer with HTTP") }
            var headers: [String: String] = [:]
            for (k, v) in http.allHeaderFields { if let k = k as? String, let v = v as? String { headers[k] = v } }
            return HTTPResponse(status: http.statusCode, headers: headers, body: data)
        } catch let e as URLError {
            throw WebDavError(Self.describe(e))
        }
    }

    static func describe(_ e: URLError) -> String {
        switch e.code {
        case .appTransportSecurityRequiresSecureConnection:
            return "macOS blocks plain http:// connections, use an https:// URL"
        case .cannotFindHost, .dnsLookupFailed: return "Server not found, check the URL"
        case .cannotConnectToHost: return "Could not connect to the server"
        case .timedOut: return "The server did not answer in time"
        case .notConnectedToInternet, .networkConnectionLost: return "No network connection"
        case .serverCertificateUntrusted, .serverCertificateHasBadDate, .serverCertificateHasUnknownRoot,
             .serverCertificateNotYetValid, .secureConnectionFailed:
            return "The server's certificate is not trusted"
        case .cancelled: return "Cancelled"
        default: return e.localizedDescription
        }
    }
}

/// The WebDAV password lives in the Keychain (generic password, no access group, §6).
enum KeychainStore {
    static let service = "app.vaulttasks.mac.webdav"
    static let account = "webdav"

    private static var base: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    static func password() -> String? {
        var q = base
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(decoding: d, as: UTF8.self)
    }

    /// An empty password removes the item. Returns false if the Keychain refused.
    @discardableResult
    static func setPassword(_ pw: String) -> Bool {
        if pw.isEmpty {
            let st = SecItemDelete(base as CFDictionary)
            return st == errSecSuccess || st == errSecItemNotFound
        }
        let data = Data(pw.utf8)
        let update = SecItemUpdate(base as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else { return false }
        var add = base
        add[kSecValueData as String] = data
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
}

/// Server URL, user and base folder in `UserDefaults`, password in the Keychain (§6).
final class WebDavSettings: @unchecked Sendable {
    private let defaults: UserDefaults
    private enum Key { static let url = "webdavURL", user = "webdavUser", folder = "webdavFolder" }

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    /// nil while no server URL is set.
    func load() -> WebDavConfig? {
        guard let url = defaults.string(forKey: Key.url), !url.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return WebDavConfig(url: url, user: defaults.string(forKey: Key.user) ?? "", password: KeychainStore.password() ?? "",
                            baseFolder: defaults.string(forKey: Key.folder) ?? "")
    }

    /// Returns false if the password could not be stored in the Keychain.
    @discardableResult
    func save(_ c: WebDavConfig) -> Bool {
        defaults.set(c.url, forKey: Key.url)
        defaults.set(c.user, forKey: Key.user)
        defaults.set(c.baseFolder, forKey: Key.folder)
        return KeychainStore.setPassword(c.password)
    }

    func clear() {
        for k in [Key.url, Key.user, Key.folder] { defaults.removeObject(forKey: k) }
        KeychainStore.setPassword("")
    }
}

/// Last agreed state per file (content hash + server token), device-local (§6). Every `put` is written through, so a
/// crash between two files never loses the record of a finished one. Cleared when the server target changes.
final class JSONSyncRecords: SyncRecords, @unchecked Sendable {
    private let lock = NSLock()
    private let file = JSONFile<[String: SyncRecord]>("sync_records_v1.json")
    private var map: [String: SyncRecord]

    init() { map = file.load() ?? [:] }

    func get(_ path: String) -> SyncRecord? { lock.withLock { map[path] } }

    func put(_ path: String, _ record: SyncRecord) {
        lock.withLock { map[path] = record; file.save(map) }
    }

    func clear() {
        lock.withLock { map = [:]; file.save(map) }
    }
}
