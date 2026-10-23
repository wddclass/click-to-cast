package com.wddclass.clicktocast.airplay

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * DNS-SD packets for AirPlay discovery. No Android APIs, so unit tests can build
 * the same bytes the TV sends. Names are written without compression; queries
 * that use compression pointers are still parsed.
 */
object Mdns {
    const val AIRPLAY_TYPE = "_airplay._tcp.local"
    const val RAOP_TYPE = "_raop._tcp.local"

    private const val SERVICES = "_services._dns-sd._udp.local"
    private const val TTL_SECONDS = 120L
    private const val PTR = 12
    private const val TXT = 16
    private const val A = 1
    private const val SRV = 33
    private const val ANY = 255

    data class ServiceRecord(
        val type: String,
        val instance: String,
        val txt: Map<String, String>,
    )

    data class Advertisement(
        val name: String,
        val hostLabel: String,
        val port: Int,
        val ipv4: String,
        val services: List<ServiceRecord>,
    )

    fun advertisement(name: String, deviceId: String, port: Int, ipv4: String): Advertisement {
        val shown = name.trim().ifBlank { "投屏点我" }
        return Advertisement(
            name = shown,
            hostLabel = hostLabel(deviceId),
            port = port,
            ipv4 = ipv4,
            services = listOf(
                ServiceRecord(AIRPLAY_TYPE, instanceLabel(shown), AirPlayTexts.airplayTxt(deviceId)),
                ServiceRecord(
                    RAOP_TYPE,
                    instanceLabel(AirPlayTexts.raopInstanceName(deviceId, shown)),
                    AirPlayTexts.raopTxt(deviceId),
                ),
            ),
        )
    }

    fun hostLabel(deviceId: String): String {
        val hex = deviceId.lowercase(Locale.US).filter { it in '0'..'9' || it in 'a'..'f' }
        return "ctc-" + hex.padEnd(12, '0').take(12)
    }

    /** One DNS label. Dots would split the name, so they become spaces. */
    fun instanceLabel(name: String): String {
        val cleaned = name.replace('.', ' ').trim().ifBlank { "投屏点我" }
        var kept = cleaned
        while (kept.toByteArray(Charsets.UTF_8).size > 63 && kept.isNotEmpty()) {
            kept = kept.dropLast(1)
        }
        return kept.ifBlank { "投屏点我" }
    }

    /** One datagram per service. [goodbye] repeats the records with TTL 0. */
    fun announce(ad: Advertisement, goodbye: Boolean = false): List<ByteArray> {
        val ttl = if (goodbye) 0L else TTL_SECONDS
        return ad.services.map { service ->
            val answers = ArrayList<Record>()
            answers += Record(SERVICES, PTR, goodbye, ttl, encodeName(service.type))
            answers += serviceRecords(ad, service, ttl, goodbye)
            pack(0, 0x8400, emptyList(), answers, emptyList())
        }
    }

    /**
     * Answer a query, or null when the packet is not a question we own.
     * The response keeps the query id and sets the response bit.
     */
    fun response(query: ByteArray, ad: Advertisement): ByteArray? {
        val parsed = parseQuery(query) ?: return null
        if (ad.port <= 0 || ipv4Bytes(ad.ipv4) == null) return null
        val answers = linkedMapOf<String, Record>()
        val extra = linkedMapOf<String, Record>()
        var matched = false
        for (question in parsed.questions) {
            if (question.klass != 1 && question.klass != 255) continue
            val qname = question.name.lowercase(Locale.US)
            if (qname == SERVICES && (question.type == PTR || question.type == ANY)) {
                matched = true
                for (service in ad.services) {
                    put(answers, Record(SERVICES, PTR, false, TTL_SECONDS, encodeName(service.type)))
                }
            }
            val hostFqdn = "${ad.hostLabel}.local"
            val hostKey = hostFqdn.lowercase(Locale.US)
            for (service in ad.services) {
                val related = serviceRecords(ad, service, TTL_SECONDS, goodbye = false)
                val typeName = service.type.lowercase(Locale.US)
                val instanceKey = "${service.instance}.${service.type}".lowercase(Locale.US)
                if (qname == typeName && (question.type == PTR || question.type == ANY)) {
                    matched = true
                    related.firstOrNull { it.type == PTR }?.let { put(answers, it) }
                    related.filter { it.type != PTR }.forEach { put(extra, it) }
                }
                if (qname == instanceKey && (question.type == SRV || question.type == ANY)) {
                    matched = true
                    related.firstOrNull { it.type == SRV }?.let { put(answers, it) }
                    related.firstOrNull { it.type == TXT }?.let { put(extra, it) }
                    related.firstOrNull { it.type == A }?.let { put(extra, it) }
                }
                if (qname == instanceKey && (question.type == TXT || question.type == ANY)) {
                    matched = true
                    related.firstOrNull { it.type == TXT }?.let { put(answers, it) }
                }
                if (qname == hostKey && (question.type == A || question.type == ANY)) {
                    matched = true
                    related.firstOrNull { it.type == A }?.let { put(answers, it) }
                }
            }
        }
        if (!matched || answers.isEmpty()) return null
        extra.keys.removeAll(answers.keys)
        return pack(parsed.id, 0x8400, parsed.questions, answers.values.toList(), extra.values.toList())
    }

    fun questionNames(packet: ByteArray): List<String> = parseQuery(packet)?.questions?.map { it.name }.orEmpty()

    /** TTL of every answer, authority, and additional record. Empty when the packet is truncated. */
    fun recordTtls(packet: ByteArray): List<Long> {
        if (packet.size < 12) return emptyList()
        val questions = u16(packet, 4)
        val records = u16(packet, 6) + u16(packet, 8) + u16(packet, 10)
        var cursor = 12
        repeat(questions) {
            cursor = readName(packet, cursor)?.end ?: return emptyList()
            cursor += 4
            if (cursor > packet.size) return emptyList()
        }
        val ttls = ArrayList<Long>(records)
        repeat(records) {
            cursor = readName(packet, cursor)?.end ?: return ttls
            if (cursor + 10 > packet.size) return ttls
            ttls += u32(packet, cursor + 4)
            val length = u16(packet, cursor + 8)
            cursor += 10 + length
        }
        return ttls
    }

    fun query(id: Int, name: String, type: Int = PTR): ByteArray =
        pack(id, 0, listOf(Question(name, type, 1)), emptyList(), emptyList())

    private fun serviceRecords(ad: Advertisement, service: ServiceRecord, ttl: Long, goodbye: Boolean): List<Record> {
        val instanceFqdn = "${service.instance}.${service.type}"
        val hostFqdn = "${ad.hostLabel}.local"
        val address = ipv4Bytes(ad.ipv4)
        val records = ArrayList<Record>(4)
        records += Record(service.type, PTR, goodbye, ttl, encodeName(instanceFqdn))
        records += Record(instanceFqdn, SRV, true, ttl, srvData(ad.port, hostFqdn))
        records += Record(instanceFqdn, TXT, true, ttl, txtData(service.txt))
        if (address != null) records += Record(hostFqdn, A, true, ttl, address)
        return records
    }

    private fun put(into: LinkedHashMap<String, Record>, record: Record) {
        val key = record.name.lowercase(Locale.US) + "|" + record.type + "|" + record.rdata.contentToString()
        if (key !in into) into[key] = record
    }

    private fun pack(
        id: Int,
        flags: Int,
        questions: List<Question>,
        answers: List<Record>,
        extra: List<Record>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        write16(out, id)
        write16(out, flags)
        write16(out, questions.size)
        write16(out, answers.size)
        write16(out, 0)
        write16(out, extra.size)
        for (question in questions) {
            out.write(encodeName(question.name))
            write16(out, question.type)
            write16(out, 1)
        }
        for (record in answers) writeRecord(out, record)
        for (record in extra) writeRecord(out, record)
        return out.toByteArray()
    }

    private fun writeRecord(out: ByteArrayOutputStream, record: Record) {
        out.write(encodeName(record.name))
        write16(out, record.type)
        write16(out, if (record.flush) 0x8001 else 0x0001)
        write32(out, record.ttl)
        write16(out, record.rdata.size)
        out.write(record.rdata)
    }

    private fun srvData(port: Int, hostFqdn: String): ByteArray {
        val host = encodeName(hostFqdn)
        val data = ByteArray(6 + host.size)
        data[4] = ((port ushr 8) and 0xFF).toByte()
        data[5] = (port and 0xFF).toByte()
        host.copyInto(data, 6)
        return data
    }

    private fun txtData(txt: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in txt) {
            if (key.isEmpty()) continue
            var text = "$key=$value".toByteArray(Charsets.UTF_8)
            if (text.size > 255) text = text.copyOf(255)
            if (text.isEmpty()) continue
            out.write(text.size)
            out.write(text)
        }
        if (out.size() == 0) out.write(0)
        return out.toByteArray()
    }

    private fun ipv4Bytes(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (index in 0..3) {
            val number = parts[index].toIntOrNull() ?: return null
            if (number !in 0..255) return null
            bytes[index] = number.toByte()
        }
        return bytes
    }

    private fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.split('.')) {
            if (label.isEmpty()) continue
            var bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.size > 63) {
                bytes = bytes.copyOf(63)
                while (bytes.isNotEmpty() && (bytes.last().toInt() and 0xC0) == 0x80) {
                    bytes = bytes.copyOf(bytes.size - 1)
                }
            }
            if (bytes.isEmpty()) continue
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun parseQuery(packet: ByteArray): Parsed? {
        if (packet.size < 12) return null
        val flags = u16(packet, 2)
        if (flags and 0x8000 != 0) return null
        val count = u16(packet, 4)
        if (count !in 1..8) return null
        var cursor = 12
        val questions = ArrayList<Question>(count)
        repeat(count) {
            val name = readName(packet, cursor) ?: return null
            cursor = name.end
            if (cursor + 4 > packet.size) return null
            val type = u16(packet, cursor)
            val klass = u16(packet, cursor + 2) and 0x7FFF
            cursor += 4
            questions += Question(name.text, type, klass)
        }
        return Parsed(u16(packet, 0), questions)
    }

    private fun readName(bytes: ByteArray, start: Int, hops: Int = 0): NameRead? {
        if (hops > 8 || start !in bytes.indices) return null
        var cursor = start
        val labels = ArrayList<String>()
        var end = start
        while (cursor < bytes.size) {
            val length = bytes[cursor].toInt() and 0xFF
            if (length == 0) return NameRead(labels.joinToString("."), cursor + 1)
            if (length and 0xC0 == 0xC0) {
                if (cursor + 1 >= bytes.size) return null
                val pointer = ((length and 0x3F) shl 8) or (bytes[cursor + 1].toInt() and 0xFF)
                if (pointer >= bytes.size) return null
                val pointed = readName(bytes, pointer, hops + 1) ?: return null
                val full = when {
                    labels.isEmpty() -> pointed.text
                    pointed.text.isEmpty() -> labels.joinToString(".")
                    else -> labels.joinToString(".") + "." + pointed.text
                }
                return NameRead(full, cursor + 2)
            }
            if (length and 0xC0 != 0 || cursor + 1 + length > bytes.size) return null
            labels += String(bytes, cursor + 1, length, Charsets.UTF_8)
            cursor += 1 + length
            end = cursor
            if (labels.size > 16) return null
        }
        if (end == start) return null
        return NameRead(labels.joinToString("."), end)
    }

    private fun write16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun write32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private data class Question(val name: String, val type: Int, val klass: Int)
    private data class Parsed(val id: Int, val questions: List<Question>)
    private data class NameRead(val text: String, val end: Int)
    private data class Record(val name: String, val type: Int, val flush: Boolean, val ttl: Long, val rdata: ByteArray)
}
