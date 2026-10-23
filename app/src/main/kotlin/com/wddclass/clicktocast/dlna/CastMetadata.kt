package com.wddclass.clicktocast.dlna

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedMetadata(
    val title: String = "",
    val mimeType: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Reads DIDL-Lite (and a small header extension) out of CurrentURIMetaData. */
object CastMetadata {
    fun parse(raw: String): ParsedMetadata {
        val normalized = unescape(raw.trim())
        if (normalized.isEmpty()) return ParsedMetadata()
        val root = parseXml(normalized)
        if (root == null) {
            return ParsedMetadata(title = regexTitle(normalized))
        }
        val acc = Acc()
        walk(root, acc)
        val headers = acc.headers.toMutableMap()
        val userAgent = acc.userAgent ?: headers.remove("User-Agent") ?: headers.remove("user-agent")
        return ParsedMetadata(
            title = acc.title.ifBlank { regexTitle(normalized) },
            mimeType = acc.mime,
            userAgent = userAgent,
            headers = headers,
        )
    }

    fun safeTitle(rawTitle: String, uri: String): String {
        val title = rawTitle.trim()
        if (title.startsWith("http://") || title.startsWith("https://")) {
            return title.substringBefore('?').substringAfterLast('/').ifBlank { "视频" }.take(80)
        }
        if (title.isNotBlank()) return title.take(80)
        return uri.substringBefore('?').substringAfterLast('/').ifBlank { "视频" }.take(80)
    }

    private fun unescape(raw: String): String {
        var text = raw
        repeat(2) {
            if (!text.startsWith("&lt;")) return text
            text = text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
        return text
    }

    private fun walk(element: Element, acc: Acc) {
        val name = localName(element).lowercase()
        val text = directText(element).trim()
        when (name) {
            "title" -> if (acc.title.isBlank() && text.isNotBlank()) acc.title = text
            "useragent", "user-agent" -> if (text.isNotBlank()) acc.userAgent = sanitizeValue(text)
            "header" -> {
                val key = sanitizeName(element.getAttribute("name").ifBlank { element.getAttribute("key") })
                if (key != null && text.isNotBlank()) acc.headers[key] = sanitizeValue(text)
            }
            "res" -> {
                mimeFromProtocol(element.getAttribute("protocolInfo"))?.let { acc.mime = it }
            }
            "longdescription", "description" -> harvestLines(text, acc)
        }
        for (child in elements(element)) walk(child, acc)
    }

    private fun harvestLines(text: String, acc: Acc) {
        if (!text.contains("User-Agent:", true) &&
            !text.contains("Referer:", true) &&
            !text.contains("Authorization:", true)
        ) {
            return
        }
        for (line in text.lineSequence()) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            val key = sanitizeName(line.substring(0, index)) ?: continue
            val value = sanitizeValue(line.substring(index + 1))
            if (value.isEmpty()) continue
            if (key.equals("User-Agent", ignoreCase = true)) acc.userAgent = value
            else acc.headers[key] = value
        }
    }

    private fun mimeFromProtocol(protocolInfo: String): String? {
        val parts = protocolInfo.split(':')
        if (parts.size < 3) return null
        val mime = parts[2].trim()
        if (mime.isEmpty() || mime == "*") return null
        return mime
    }

    private fun regexTitle(xml: String): String {
        val match = Regex("<(?:[A-Za-z0-9]+:)?title>(.*?)</(?:[A-Za-z0-9]+:)?title>", RegexOption.IGNORE_CASE)
            .find(xml) ?: return ""
        return match.groupValues[1].trim()
    }

    private fun sanitizeName(raw: String): String? {
        val name = raw.trim().take(64)
        return name.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }
    }

    private fun sanitizeValue(raw: String): String =
        raw.replace("\r", "").replace("\n", "").trim().take(512)

    private fun parseXml(xml: String): Element? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
        }
        factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8)).documentElement
    } catch (_: Exception) {
        null
    }

    private fun localName(node: Node): String {
        val local = node.localName
        if (!local.isNullOrEmpty()) return local
        return node.nodeName.substringAfter(':')
    }

    private fun elements(parent: Node): List<Element> {
        val found = mutableListOf<Element>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) found += child
        }
        return found
    }

    private fun directText(element: Element): String {
        val builder = StringBuilder()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.TEXT_NODE || child.nodeType == Node.CDATA_SECTION_NODE) {
                builder.append(child.nodeValue ?: "")
            }
        }
        return builder.toString()
    }

    private class Acc {
        var title: String = ""
        var mime: String? = null
        var userAgent: String? = null
        val headers: MutableMap<String, String> = linkedMapOf()
    }
}
