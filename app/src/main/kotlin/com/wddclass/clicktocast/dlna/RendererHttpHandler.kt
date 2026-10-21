package com.wddclass.clicktocast.dlna

class RendererHttpHandler(
    private val renderer: DlnaRenderer,
    private val profile: () -> DeviceProfile,
    private val events: GenaPublisher,
) {
    fun handle(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifBlank { "/" }
        return when {
            request.method == "GET" && path == DmrPaths.DESCRIPTION -> {
                DmrLog.i(
                    "http description from ${request.remoteIp} ua=${request.header("user-agent") ?: "-"}",
                )
                xml(DescriptionXml.device(profile()))
            }
            request.method == "GET" && path == DmrPaths.SCPD_AVT -> xml(ScpdXml.avTransport())
            request.method == "GET" && path == DmrPaths.SCPD_RCS -> xml(ScpdXml.rendering())
            request.method == "GET" && path == DmrPaths.SCPD_CMS -> xml(ScpdXml.connection())
            request.method == "GET" && path == DmrPaths.ICON_48 -> png(DeviceIcons.small)
            request.method == "GET" && path == DmrPaths.ICON_120 -> png(DeviceIcons.large)
            request.method == "POST" && path == DmrPaths.CONTROL_AVT ->
                soap(request) { action, args -> renderer.handleAv(action, args, request.header("user-agent")) }
            request.method == "POST" && path == DmrPaths.CONTROL_RCS ->
                soap(request) { action, args -> renderer.handleRc(action, args) }
            request.method == "POST" && path == DmrPaths.CONTROL_CMS ->
                soap(request) { action, args -> renderer.handleCm(action, args) }
            request.method == "SUBSCRIBE" && DmrPaths.serviceForEvent(path) != null -> subscribe(path, request)
            request.method == "UNSUBSCRIBE" && DmrPaths.serviceForEvent(path) != null -> unsubscribe(request)
            request.method == "GET" && (path == "/" || path == "/dmr") ->
                text(200, "OK", "投屏点我 DLNA MediaRenderer\n")
            else -> text(404, "Not Found", "not found\n")
        }
    }

    private fun soap(
        request: HttpRequest,
        dispatch: (action: String, args: Map<String, String>) -> SoapResult,
    ): HttpResponse {
        val body = SoapCodec.decodeBody(request.body)
        if (body.isBlank()) {
            val result = SoapResult.fault(402, "Invalid Args")
            DmrLog.i("soap ${request.path} empty body from ${request.remoteIp} bytes=${request.body.size} -> HTTP 500 UPnP 402")
            return soapResponse(result)
        }
        val action = SoapCodec.actionName(request.header("soapaction"), body)
        if (action.isBlank()) {
            val result = SoapResult.fault(401, "Invalid Action")
            DmrLog.i(
                "soap ${request.path} unrecognized from ${request.remoteIp} " +
                    "header=${request.header("soapaction")} body=${body.take(160)} -> HTTP 500 UPnP 401",
            )
            return soapResponse(result)
        }
        val args = SoapCodec.arguments(body)
        val result = dispatch(action, args)
        logSoap(request, action, args, result)
        return soapResponse(result)
    }

    private fun logSoap(request: HttpRequest, action: String, args: Map<String, String>, result: SoapResult) {
        val shown = if (args.isEmpty()) {
            "(none)"
        } else {
            args.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "$key=${previewArg(key, value)}"
            }
        }
        val code = ERROR_CODE.find(result.body)?.groupValues?.get(1)
        val why = ERROR_DESC.find(result.body)?.groupValues?.get(1)
        val outcome = buildString {
            append("HTTP ")
            append(result.httpStatus)
            if (code != null) append(" UPnP ").append(code)
            if (!why.isNullOrBlank()) append(' ').append(why)
        }
        DmrLog.i("soap ${request.path} $action from ${request.remoteIp} args=$shown -> $outcome")
    }

    private fun previewArg(key: String, value: String): String {
        if (value.isEmpty()) return "(empty)"
        val shown = if (key.contains("uri", ignoreCase = true)) redactUrl(value) else value
        return if (shown.length <= 180) shown else shown.take(180) + "…(${value.length})"
    }

    private fun subscribe(path: String, request: HttpRequest): HttpResponse {
        val service = DmrPaths.serviceForEvent(path) ?: return text(404, "Not Found", "")
        val result = events.subscribe(
            service,
            request.header("callback"),
            request.header("timeout"),
            request.header("sid"),
        ) ?: return text(412, "Precondition Failed", "")
        return HttpResponse(
            200,
            "OK",
            linkedMapOf(
                "SID" to result.sid,
                "TIMEOUT" to "Second-${result.timeoutSeconds}",
                "CONTENT-LENGTH" to "0",
                "CONNECTION" to "close",
            ),
        )
    }

    private fun unsubscribe(request: HttpRequest): HttpResponse {
        return if (events.unsubscribe(request.header("sid"))) {
            HttpResponse(200, "OK", linkedMapOf("CONTENT-LENGTH" to "0", "CONNECTION" to "close"))
        } else {
            text(412, "Precondition Failed", "")
        }
    }

    private fun soapResponse(result: SoapResult): HttpResponse {
        val reason = if (result.httpStatus == 200) "OK" else "Internal Server Error"
        return HttpResponse(
            result.httpStatus,
            reason,
            linkedMapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "EXT" to "",
                "CONNECTION" to "close",
            ),
            result.body.toByteArray(Charsets.UTF_8),
        )
    }

    private fun xml(body: String): HttpResponse = HttpResponse(
        200,
        "OK",
        linkedMapOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "Cache-Control" to "max-age=60",
        ),
        body.toByteArray(Charsets.UTF_8),
    )

    private fun png(bytes: ByteArray): HttpResponse {
        if (bytes.isEmpty()) return text(404, "Not Found", "")
        return HttpResponse(
            200,
            "OK",
            linkedMapOf(
                "Content-Type" to "image/png",
                "Cache-Control" to "max-age=86400",
            ),
            bytes,
        )
    }

    private fun text(status: Int, reason: String, body: String): HttpResponse = HttpResponse(
        status,
        reason,
        linkedMapOf("Content-Type" to "text/plain; charset=utf-8"),
        body.toByteArray(Charsets.UTF_8),
    )

    companion object {
        private val ERROR_CODE = Regex("<errorCode>(\\d+)</errorCode>")
        private val ERROR_DESC = Regex("<errorDescription>([^<]*)</errorDescription>")
    }
}
