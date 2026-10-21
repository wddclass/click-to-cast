package com.wddclass.clicktocast.dlna

import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class SoapResult(val httpStatus: Int, val body: String) {
    companion object {
        fun ok(serviceUrn: String, action: String, outputs: Map<String, String> = emptyMap()): SoapResult {
            val safeAction = action.filter { it.isLetterOrDigit() }.ifBlank { "Action" }
            val xml = buildString {
                append("""<?xml version="1.0" encoding="utf-8"?>""")
                append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
                append("""<u:${safeAction}Response xmlns:u="$serviceUrn">""")
                for ((name, value) in outputs) {
                    append('<').append(name).append('>')
                    append(XmlText.escape(value))
                    append("</").append(name).append('>')
                }
                append("</u:${safeAction}Response></s:Body></s:Envelope>")
            }
            return SoapResult(200, xml)
        }

        fun fault(code: Int, description: String): SoapResult {
            val xml = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$code</errorCode>
          <errorDescription>${XmlText.escape(description)}</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""
            return SoapResult(500, xml)
        }
    }
}

/**
 * Lenient SOAP reader. Phone apps send empty metadata, NOT_IMPLEMENTED, unescaped DIDL,
 * missing InstanceID, odd prefixes, and SOAPACTION with or without quotes.
 * A strict document parse must not throw away a usable CurrentURI.
 */
object SoapCodec {
    fun decodeBody(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 160)), Charsets.ISO_8859_1)
        val declared = ENCODING.find(head)?.groupValues?.get(1)?.lowercase()
        val charset = when (declared) {
            null, "", "utf-8", "utf8" -> Charsets.UTF_8
            "gbk", "gb2312", "gb18030" -> runCatching { charset("GBK") }.getOrDefault(Charsets.UTF_8)
            else -> runCatching { charset(declared) }.getOrDefault(Charsets.UTF_8)
        }
        return String(bytes, charset).trim().trimStart('\uFEFF')
    }

    fun actionName(soapActionHeader: String?, body: String): String {
        val fromHeader = actionFromHeader(soapActionHeader)
        if (fromHeader.isNotEmpty()) return fromHeader
        val fromDom = bodyAction(body)
        if (fromDom.isNotEmpty()) return canonical(fromDom)
        return looseAction(body)
    }

    fun canonical(action: String): String {
        val trimmed = action.trim()
        return KNOWN.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    fun arguments(body: String): Map<String, String> {
        val loose = looseArguments(body)
        val dom = domArguments(body)
        val names = LinkedHashSet<String>()
        names += loose.keys
        names += dom.keys
        val merged = linkedMapOf<String, String>()
        for (name in names) {
            val fromDom = dom[name]
            val fromLoose = loose[name]
            merged[name] = when {
                !fromDom.isNullOrBlank() -> unescapeXml(fromDom)
                fromLoose != null -> fromLoose
                fromDom != null -> fromDom
                else -> ""
            }
        }
        return merged
    }

    fun arg(args: Map<String, String>, name: String): String? =
        args.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun unescapeXml(raw: String): String {
        if (!raw.contains('&')) return raw
        var text = raw
        repeat(2) {
            if (!text.contains('&')) return text
            val next = unescapeOnce(text)
            if (next == text) return text
            text = next
        }
        return text
    }

    private fun actionFromHeader(header: String?): String {
        if (header.isNullOrBlank()) return ""
        var value = header.trim()
        while (value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
        ) {
            value = value.substring(1, value.length - 1).trim()
        }
        value = value.trim('"', '\'').substringBefore(';').trim()
        val raw = if ('#' in value) value.substringAfterLast('#') else value
        val name = raw.trim().trim('"').substringBefore('"').substringBefore(' ').trim()
        if (!name.matches(ACTION)) return ""
        return canonical(name)
    }

    private fun looseAction(body: String): String {
        for (match in ACTION_TAG.findAll(body)) {
            val name = match.groupValues[1]
            if (name.equals("xml", true)) continue
            if (SKIP_TAGS.contains(name.lowercase())) continue
            val known = canonical(name)
            if (KNOWN.any { it.equals(known, true) }) return known
        }
        return ""
    }

    private fun looseArguments(body: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (name in ARG_NAMES) {
            val match = tagPatterns.getValue(name).find(body) ?: continue
            values[name] = unwrap(match.groupValues.getOrElse(1) { "" })
        }
        return values
    }

    private fun unwrap(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("<![CDATA[") && text.endsWith("]]>")) {
            return text.substring(9, text.length - 3).trim()
        }
        return unescapeXml(text).trim()
    }

    private fun unescapeOnce(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '&') {
                out.append(raw[index])
                index += 1
                continue
            }
            val semi = raw.indexOf(';', index + 1)
            if (semi < 0 || semi - index > 12) {
                out.append('&')
                index += 1
                continue
            }
            val token = raw.substring(index, semi + 1)
            val decoded = when (token) {
                "&amp;" -> "&"
                "&lt;" -> "<"
                "&gt;" -> ">"
                "&quot;" -> "\""
                "&apos;" -> "'"
                else -> decodeNumeric(token)
            }
            if (decoded == null) {
                out.append('&')
                index += 1
            } else {
                out.append(decoded)
                index = semi + 1
            }
        }
        return out.toString()
    }

    private fun decodeNumeric(token: String): String? {
        val value = when {
            token.startsWith("&#x", true) && token.endsWith(";") ->
                token.substring(3, token.length - 1).toIntOrNull(16)
            token.startsWith("&#") && token.endsWith(";") ->
                token.substring(2, token.length - 1).toIntOrNull()
            else -> null
        } ?: return null
        if (value !in 0..0x10FFFF || value in 0xD800..0xDFFF) return null
        return String(Character.toChars(value))
    }

    private fun domArguments(body: String): Map<String, String> {
        val root = parse(body) ?: return emptyMap()
        val soapBody = findFirst(root) { localName(it).equals("Body", ignoreCase = true) } ?: return emptyMap()
        val action = firstElement(soapBody) ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        for (child in elements(action)) {
            val name = canonicalArg(localName(child))
            if (name.isBlank() || name in values) continue
            values[name] = textOf(child).trim()
        }
        return values
    }

    private fun canonicalArg(name: String): String =
        ARG_NAMES.firstOrNull { it.equals(name, ignoreCase = true) } ?: name

    private fun bodyAction(body: String): String {
        val root = parse(body) ?: return ""
        val soapBody = findFirst(root) { localName(it).equals("Body", ignoreCase = true) } ?: root
        return firstElement(soapBody)?.let { localName(it) }.orEmpty()
    }

    private fun parse(xml: String): Element? {
        val text = xml.trim().trimStart('\uFEFF')
        if (text.isBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Exception) {
                // Some Android XML stacks don't expose this feature; secure processing still applies.
            }
            val builder = factory.newDocumentBuilder()
            builder.parse(text.byteInputStream(Charsets.UTF_8)).documentElement
        } catch (_: Exception) {
            null
        }
    }

    private fun localName(node: Node): String {
        val local = node.localName
        if (!local.isNullOrEmpty()) return local
        val raw = node.nodeName ?: return ""
        return raw.substringAfter(':')
    }

    private fun firstElement(parent: Node): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) return child
        }
        return null
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

    private fun findFirst(root: Element, match: (Element) -> Boolean): Element? {
        if (match(root)) return root
        for (child in elements(root)) {
            findFirst(child, match)?.let { return it }
        }
        return null
    }

    private fun textOf(element: Element): String {
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

    private val ACTION = Regex("[A-Za-z][A-Za-z0-9]*")
    private val ACTION_TAG = Regex("""<(?:[\w.-]+:)?([A-Za-z][A-Za-z0-9]*)\b""")
    private val ENCODING = Regex("""encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""", RegexOption.IGNORE_CASE)
    private val SKIP_TAGS = setOf("envelope", "body", "header", "fault")
    private val KNOWN = listOf(
        "SetAVTransportURI",
        "SetNextAVTransportURI",
        "Play",
        "Pause",
        "Stop",
        "Seek",
        "GetPositionInfo",
        "GetTransportInfo",
        "GetMediaInfo",
        "GetDeviceCapabilities",
        "GetTransportSettings",
        "GetCurrentTransportActions",
        "GetVolume",
        "SetVolume",
        "GetMute",
        "SetMute",
        "ListPresets",
        "SelectPreset",
        "GetProtocolInfo",
        "GetCurrentConnectionIDs",
        "GetCurrentConnectionInfo",
    )
    private val ARG_NAMES = listOf(
        "InstanceID",
        "CurrentURI",
        "CurrentURIMetaData",
        "NextURI",
        "NextURIMetaData",
        "Speed",
        "Unit",
        "Target",
        "Channel",
        "DesiredVolume",
        "DesiredMute",
        "PresetName",
        "ConnectionID",
    )
    private val tagPatterns: Map<String, Regex> = ARG_NAMES.associateWith { name ->
        Regex(
            """<(?:[\w.-]+:)?$name\b[^>]*/>|<(?:[\w.-]+:)?$name\b[^>]*>(.*?)</(?:[\w.-]+:)?$name\s*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
