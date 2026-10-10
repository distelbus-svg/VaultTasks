import Testing
import Foundation
@testable import VaultTasksCore

@Suite struct PropfindParserTests {
    @Test func readsEtagSizeModifiedAndCollectionFlagIgnoringFailedPropstats() throws {
        let xml = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response>
            <d:href>/dav/Vault/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/Vault/a%20b.md</d:href>
            <d:propstat>
              <d:prop><d:getetag>"abc123"</d:getetag><d:getcontentlength>42</d:getcontentlength>
                <d:getlastmodified>Fri, 02 Oct 2026 10:00:00 GMT</d:getlastmodified><d:resourcetype/></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status>
            </d:propstat>
            <d:propstat><d:prop><d:quota-used-bytes/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
          </d:response>
        </d:multistatus>
        """
        let entries = try PropfindParser.parse(xml)
        #expect(entries.count == 2)
        let (dir, file) = (entries[0], entries[1])
        #expect(dir.isCollection)
        #expect(dir.etag == nil)
        #expect(!file.isCollection)
        #expect(file.href == "/dav/Vault/a%20b.md")
        #expect(file.etag == "\"abc123\"")
        #expect(file.size == 42)
        #expect(file.lastModified == "Fri, 02 Oct 2026 10:00:00 GMT")
    }

    @Test func worksWithOtherNamespacePrefixesAndNoEtag() throws {
        let xml = """
        <D:multistatus xmlns:D="DAV:"><D:response><D:href>/x.md</D:href>
        <D:propstat><D:prop><D:getcontentlength>1</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response></D:multistatus>
        """
        let e = try PropfindParser.parse(xml)
        #expect(e.count == 1)
        #expect(e[0].etag == nil)
        #expect(e[0].size == 1)
    }

    @Test func ignoresElementsOutsideTheDavNamespaceAndRejectsBrokenXml() throws {
        let other = #"<x:multistatus xmlns:x="urn:other"><x:response><x:href>/a.md</x:href></x:response></x:multistatus>"#
        #expect(try PropfindParser.parse(other).isEmpty)
        #expect(throws: (any Error).self) { try PropfindParser.parse("<d:multistatus xmlns:d=\"DAV:\"><d:response>") }
    }
}
