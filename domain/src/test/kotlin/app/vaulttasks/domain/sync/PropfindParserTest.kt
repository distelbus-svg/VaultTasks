package app.vaulttasks.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropfindParserTest {
    @Test fun `reads etag, size, modified and collection flag, ignoring failed propstats`() {
        val xml = """<?xml version="1.0"?>
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
            </d:multistatus>"""
        val (dir, file) = PropfindParser.parse(xml)
        assertTrue(dir.isCollection)
        assertNull(dir.etag)
        assertFalse(file.isCollection)
        assertEquals("/dav/Vault/a%20b.md", file.href)
        assertEquals("\"abc123\"", file.etag)
        assertEquals(42L, file.size)
        assertEquals("Fri, 02 Oct 2026 10:00:00 GMT", file.lastModified)
    }

    @Test fun `works with other namespace prefixes and no etag`() {
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response><D:href>/x.md</D:href>
            <D:propstat><D:prop><D:getcontentlength>1</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
            </D:response></D:multistatus>"""
        val e = PropfindParser.parse(xml).single()
        assertNull(e.etag)
        assertEquals(1L, e.size)
    }
}
