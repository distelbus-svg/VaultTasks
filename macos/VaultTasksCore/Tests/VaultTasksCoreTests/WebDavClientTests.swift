import Testing
import Foundation
@testable import VaultTasksCore

private final class FakeTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var log: [HTTPRequest] = []
    var handler: @Sendable (HTTPRequest) -> HTTPResponse = { _ in HTTPResponse(status: 500) }
    var requests: [HTTPRequest] { lock.withLock { log } }
    func count(_ method: String) -> Int { requests.filter { $0.method == method }.count }
    func send(_ r: HTTPRequest) async throws -> HTTPResponse {
        lock.withLock { log.append(r) }
        return handler(r)
    }
}

private let httpDate = "Tue, 03 Jun 2008 11:05:30 GMT"

private func multistatus(etag: String? = "\"e1\"", size: Int = 5, collection: Bool = false) -> HTTPResponse {
    let tag = etag.map { "<d:getetag>\($0)</d:getetag>" } ?? ""
    let type = collection ? "<d:resourcetype><d:collection/></d:resourcetype>" : "<d:resourcetype/>"
    let xml = """
    <?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/x</d:href><d:propstat><d:prop>\
    \(tag)<d:getlastmodified>\(httpDate)</d:getlastmodified><d:getcontentlength>\(size)</d:getcontentlength>\(type)\
    </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>
    """
    return HTTPResponse(status: 207, body: Data(xml.utf8))
}

private func config(url: String = "https://h.example/dav/", folder: String = "Vault") -> WebDavConfig {
    WebDavConfig(url: url, user: "u", password: "p", baseFolder: folder)
}

@Suite struct WebDavClientTests {
    private let t = FakeTransport()
    private func client(folder: String = "Vault") throws -> WebDavClient { try WebDavClient(config: config(folder: folder), transport: t) }

    private func message(_ block: () async throws -> Void) async -> String? {
        do { try await block(); return nil } catch let e as WebDavError { return e.message } catch { return "other: \(error)" }
    }

    @Test func urlsAreEncodedPerSegmentBelowTheBaseFolder() throws {
        let c = try WebDavClient(config: config(url: " https://h.example/dav// ", folder: "/Vault Tasks/sub/"), transport: t)
        #expect(try c.url(["Tasks", "a b#1.md"]).absoluteString == "https://h.example/dav/Vault%20Tasks/sub/Tasks/a%20b%231.md")
        #expect(try c.url([], collection: true).absoluteString == "https://h.example/dav/Vault%20Tasks/sub/")
        #expect(try c.url(["Ü.md"]).absoluteString.hasSuffix("/%C3%9C.md"))
    }

    @Test func invalidServerUrlIsRejected() {
        for bad in ["", "example.com/dav", "ftp://h.example", "https://"] {
            #expect(throws: WebDavError.self) { try WebDavClient(config: config(url: bad), transport: t) }
        }
    }

    @Test func statSendsBasicAuthAndDepthZeroAndReadsEtagAndTime() async throws {
        t.handler = { _ in multistatus() }
        let s = try await client().stat("Tasks/a.md")
        let req = t.requests[0]
        #expect(req.method == "PROPFIND")
        #expect(req.headers["Depth"] == "0")
        #expect(req.headers["Authorization"] == "Basic dTpw") // base64("u:p")
        #expect(req.url.absoluteString == "https://h.example/dav/Vault/Tasks/a.md")
        #expect(s?.token == "\"e1\"")
        #expect(s?.modified == ISO8601DateFormatter().date(from: "2008-06-03T11:05:30Z").map { Int64($0.timeIntervalSince1970 * 1000) })
    }

    @Test func serverWithoutEtagGetsASyntheticToken() async throws {
        t.handler = { _ in multistatus(etag: nil) }
        #expect(try await client().stat("a.md")?.token == "lm|\(httpDate)|5")
    }

    @Test func missingFileAndCollectionsAreNotFiles() async throws {
        t.handler = { _ in HTTPResponse(status: 404) }
        #expect(try await client().stat("a.md") == nil)
        #expect(try await client().get("a.md") == nil)
        t.handler = { _ in multistatus(collection: true) }
        #expect(try await client().stat("dir") == nil)
    }

    @Test func getTakesTheTokenFromPropfindAndTheBytesFromGet() async throws {
        t.handler = { r in r.method == "PROPFIND" ? multistatus(etag: "\"e7\"") : HTTPResponse(status: 200, body: Data("hello".utf8)) }
        let d = try await client().get("a.md")
        #expect(d?.token == "\"e7\"")
        #expect(d?.bytes == Data("hello".utf8))
        #expect(t.requests.map(\.method) == ["PROPFIND", "GET"])
    }

    @Test func createUsesIfNoneMatchAndMakesEveryDirectoryOnce() async throws {
        t.handler = { r in
            switch r.method {
            case "MKCOL": HTTPResponse(status: r.url.path.hasSuffix("/Vault/") ? 405 : 201) // base already exists
            default: HTTPResponse(status: 201, headers: ["ETag": "\"n1\""])
            }
        }
        let c = try client()
        #expect(try await c.put("Tasks/a.md", Data("x".utf8), expectedToken: nil) == .ok(token: "\"n1\""))
        let put = t.requests.first { $0.method == "PUT" }!
        #expect(put.headers["If-None-Match"] == "*")
        #expect(put.headers["If-Match"] == nil)
        #expect(put.headers["Content-Type"] == "text/markdown")
        #expect(t.requests.filter { $0.method == "MKCOL" }.map(\.url.absoluteString)
                == ["https://h.example/dav/Vault/", "https://h.example/dav/Vault/Tasks/"])
        // A second upload below the same folders makes no more MKCOL calls.
        _ = try await c.put("Tasks/b.md", Data("y".utf8), expectedToken: nil)
        #expect(t.count("MKCOL") == 2)
    }

    @Test func strongEtagIsSentAsIfMatchAnd412MeansPreconditionFailed() async throws {
        t.handler = { r in r.method == "MKCOL" ? HTTPResponse(status: 405) : HTTPResponse(status: 412) }
        let r = try await client().put("a.md", Data("x".utf8), expectedToken: "\"old\"")
        #expect(r == .preconditionFailed)
        #expect(t.requests.first { $0.method == "PUT" }?.headers["If-Match"] == "\"old\"")
    }

    @Test func syntheticTokenIsCheckedByHandAndAMismatchSendsNoPut() async throws {
        t.handler = { _ in multistatus(etag: nil) } // current token: lm|date|5
        let r = try await client().put("a.md", Data("x".utf8), expectedToken: "lm|other|9")
        #expect(r == .preconditionFailed)
        #expect(t.count("PUT") == 0)
    }

    @Test func syntheticTokenMatchProceedsAndANonStrongEtagIsReadBackWithStat() async throws {
        t.handler = { r in
            switch r.method {
            case "PROPFIND": multistatus(etag: nil, size: 5)
            case "MKCOL": HTTPResponse(status: 405)
            default: HTTPResponse(status: 204, headers: ["ETag": "W/\"weak\""])
            }
        }
        let r = try await client().put("a.md", Data("x".utf8), expectedToken: "lm|\(httpDate)|5")
        #expect(r == .ok(token: "lm|\(httpDate)|5"))
        let put = t.requests.first { $0.method == "PUT" }!
        #expect(put.headers["If-Match"] == nil && put.headers["If-None-Match"] == nil)
    }

    @Test func httpErrorsBecomeReadableMessages() async throws {
        let c = try client()
        t.handler = { _ in HTTPResponse(status: 401) }
        #expect(await message { _ = try await c.stat("a.md") } == "Not authorized (HTTP 401), check user and password")
        t.handler = { _ in HTTPResponse(status: 302, headers: ["Location": "https://elsewhere"]) }
        #expect(await message { _ = try await c.stat("a.md") } == "Server redirected (HTTP 302), use the final URL")
        t.handler = { _ in HTTPResponse(status: 500) }
        #expect(await message { _ = try await c.stat("a.md") } == "Server answered HTTP 500")
        t.handler = { _ in HTTPResponse(status: 207, body: Data("not xml at all <".utf8)) }
        #expect(await message { _ = try await c.stat("a.md") } == "The server's answer could not be read (not WebDAV?)")
    }

    @Test func connectionTestDistinguishesMissingFolderFolderAndFile() async throws {
        let c = try client()
        t.handler = { _ in HTTPResponse(status: 404) }
        #expect(try await c.test().hasPrefix("Connected. The folder doesn't exist yet"))
        t.handler = { _ in multistatus(collection: true) }
        #expect(try await c.test() == "Connected.")
        #expect(t.requests.last?.url.absoluteString == "https://h.example/dav/Vault/")
        t.handler = { _ in multistatus(collection: false) }
        #expect(try await c.test() == "The remote folder path points to a file.")
    }

    @Test func httpDatesParseToEpochMillis() {
        #expect(WebDavClient.millis(httpDate) == 1_212_491_130_000)
        #expect(WebDavClient.millis("Tue, 3 Jun 2008 11:05:30 GMT") == 1_212_491_130_000)
        #expect(WebDavClient.millis("yesterday") == nil)
        #expect(WebDavClient.millis(nil) == nil)
    }
}

@Suite struct AutoSyncTests {
    @Test func gateEnforcesTheMinimumGapAndRestartsItOnlyOnARun() {
        var g = AutoSyncGate()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let first = g.tryStart(now: t0, minGap: 60)
        let tooSoon = g.tryStart(now: t0.addingTimeInterval(59), minGap: 60) // blocked runs do not move the window
        let afterGap = g.tryStart(now: t0.addingTimeInterval(60), minGap: 60)
        let noGap = g.tryStart(now: t0.addingTimeInterval(60), minGap: 0) // gap 0: always
        #expect(first && !tooSoon && afterGap && noGap)
    }

    @Test func aBackwardsClockJumpNeverBlocks() {
        var g = AutoSyncGate()
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        let first = g.tryStart(now: t0, minGap: 60)
        let earlier = g.tryStart(now: t0.addingTimeInterval(-3600), minGap: 60)
        #expect(first && earlier)
    }

    @Test func summaryLineCountsPulledPushedAndFailed() {
        var r = SyncReport()
        #expect(r.summaryLine == "0 pulled, 0 pushed")
        r.pulled = ["a"]; r.pushed = ["b", "c"]
        #expect(r.summaryLine == "1 pulled, 2 pushed")
        r.failed = [SyncFailure(path: "d", message: "x")]
        #expect(r.summaryLine == "1 pulled, 2 pushed, 1 failed")
    }
}
