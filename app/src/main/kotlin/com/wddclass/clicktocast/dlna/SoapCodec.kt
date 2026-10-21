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

object SoapCodec {
    fun actionName(soapActionHeader: String?, body: String): String {
        val fromHeader = soapActionHeader
            ?.trim()
            ?.trim('"')
            ?.substringAfter('#', "")
            ?.substringBefore('"')
            ?.trim()
            .orEmpty()
        if (fromHeader.matches(ACTION)) return fromHeader
        return bodyAction(body)
    }

    fun arguments(body: String): Map<String, String> {
        val root = parse(body) ?: return emptyMap()
        val soapBody = findFirst(root) { localName(it).equals("Body", ignoreCase = true) } ?: return emptyMap()
        val action = firstElement(soapBody) ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        for (child in elements(action)) {
            values[localName(child)] = textOf(child)
        }
        return values
    }

    fun arg(args: Map<String, String>, name: String): String? =
        args.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun bodyAction(body: String): String {
        val root = parse(body) ?: return ""
        val soapBody = findFirst(root) { localName(it).equals("Body", ignoreCase = true) } ?: return ""
        return firstElement(soapBody)?.let { localName(it) }.orEmpty()
    }

    private fun parse(xml: String): Element? {
        if (xml.isBlank()) return null
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
            builder.parse(xml.byteInputStream(Charsets.UTF_8)).documentElement
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
}
