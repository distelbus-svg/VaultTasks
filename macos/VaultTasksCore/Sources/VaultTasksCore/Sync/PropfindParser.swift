import Foundation

/// Reads the parts of a WebDAV `207 Multi-Status` PROPFIND answer that sync needs.
public enum PropfindParser {
    public struct Entry: Equatable, Sendable {
        public let href: String
        public let isCollection: Bool
        public let etag: String?
        public let lastModified: String?
        public let size: Int64?
    }

    private static let dav = "DAV:"

    private final class Node {
        let ns: String?
        let local: String
        var text = ""
        var children: [Node] = []
        init(ns: String?, local: String) { self.ns = ns; self.local = local }

        func kids(_ name: String) -> [Node] { children.filter { $0.ns == PropfindParser.dav && $0.local == name } }
        func firstText(_ name: String) -> String? {
            guard let t = kids(name).first?.text.ktTrimmed, !t.isEmpty else { return nil }
            return t
        }
    }

    /// Namespace-aware tree builder. External entities are never resolved (XXE), and a DOCTYPE is not needed by any server.
    private final class Builder: NSObject, XMLParserDelegate {
        let root = Node(ns: nil, local: "#root")
        private var stack: [Node] = []
        override init() { super.init(); stack = [root] }

        func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?,
                    attributes attributeDict: [String: String] = [:]) {
            // `elementName` may or may not carry the prefix depending on the parser backend; the local part is what counts.
            let local = elementName.split(separator: ":", omittingEmptySubsequences: false).last.map(String.init) ?? elementName
            let n = Node(ns: namespaceURI, local: local)
            stack.last?.children.append(n)
            stack.append(n)
        }
        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            if stack.count > 1 { stack.removeLast() }
        }
        func parser(_ parser: XMLParser, foundCharacters string: String) { stack.last?.text += string }
        func parser(_ parser: XMLParser, foundCDATA CDATABlock: Data) { stack.last?.text += String(decoding: CDATABlock, as: UTF8.self) }
    }

    public static func parse(_ xml: String) throws -> [Entry] {
        let parser = XMLParser(data: Data(xml.utf8))
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        let b = Builder()
        parser.delegate = b
        guard parser.parse() else { throw parser.parserError ?? CocoaError(.fileReadCorruptFile) }

        var responses: [Node] = []
        func collect(_ n: Node) {
            if n.ns == dav && n.local == "response" { responses.append(n) }
            n.children.forEach(collect)
        }
        collect(b.root)

        return responses.compactMap { response in
            guard let href = response.firstText("href") else { return nil }
            // Only properties the server reports with a 2xx status count.
            let props = response.kids("propstat")
                .filter { ps in ps.firstText("status").map { $0.contains(" 2") } ?? true }
                .flatMap { $0.kids("prop") }
            func prop(_ name: String) -> String? { props.lazy.compactMap { $0.firstText(name) }.first }
            return Entry(
                href: href,
                isCollection: props.contains { p in p.kids("resourcetype").contains { !$0.kids("collection").isEmpty } },
                etag: prop("getetag"),
                lastModified: prop("getlastmodified"),
                size: prop("getcontentlength").flatMap { Int64($0) }
            )
        }
    }
}
