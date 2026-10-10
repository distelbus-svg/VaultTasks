package app.vaulttasks.domain.sync

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Reads the parts of a WebDAV `207 Multi-Status` PROPFIND answer that sync needs. */
object PropfindParser {
    data class Entry(
        val href: String,
        val isCollection: Boolean,
        val etag: String?,
        val lastModified: String?,
        val size: Long?,
    )

    private const val DAV = "DAV:"

    fun parse(xml: String): List<Entry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Hardening against XXE; not every parser (Android's) knows these, so best effort.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val responses = doc.getElementsByTagNameNS(DAV, "response")
        return (0 until responses.length).mapNotNull { i ->
            val response = responses.item(i) as Element
            val href = response.firstText("href") ?: return@mapNotNull null
            // Only properties the server reports with a 2xx status count.
            val props = response.children("propstat")
                .filter { ps -> ps.firstText("status")?.contains(" 2") != false }
                .flatMap { it.children("prop") }
            fun prop(name: String) = props.firstNotNullOfOrNull { it.firstText(name) }
            Entry(
                href = href,
                isCollection = props.any { p -> p.children("resourcetype").any { it.children("collection").isNotEmpty() } },
                etag = prop("getetag"),
                lastModified = prop("getlastmodified"),
                size = prop("getcontentlength")?.toLongOrNull(),
            )
        }
    }

    private fun Element.children(name: String): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.namespaceURI == DAV && n.localName == name) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.firstText(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
