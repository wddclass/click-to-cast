package com.wddclass.clicktocast.dlna

class RendererHttpHandler(
    private val renderer: DlnaRenderer,
    private val profile: () -> DeviceProfile,
    private val events: GenaPublisher,
) {
    fun handle(request: HttpRequest): HttpResponse {
        val path = request.path.trimEnd('/').ifBlank { "/" }
        return when {
            request.method == "GET" && path == DmrPaths.DESCRIPTION ->
                xml(DescriptionXml.device(profile()))
            request.method == "GET" && path == DmrPaths.SCPD_AVT -> xml(ScpdXml.avTransport())
            request.method == "GET" && path == DmrPaths.SCPD_RCS -> xml(ScpdXml.rendering())
            request.method == "GET" && path == DmrPaths.SCPD_CMS -> xml(ScpdXml.connection())
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
        val body = String(request.body, Charsets.UTF_8)
        if (body.isBlank()) return soapResponse(SoapResult.fault(402, "Invalid Args"))
        val action = SoapCodec.actionName(request.header("soapaction"), body)
        if (action.isBlank()) return soapResponse(SoapResult.fault(401, "Invalid Action"))
        return soapResponse(dispatch(action, SoapCodec.arguments(body)))
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

    private fun text(status: Int, reason: String, body: String): HttpResponse = HttpResponse(
        status,
        reason,
        linkedMapOf("Content-Type" to "text/plain; charset=utf-8"),
        body.toByteArray(Charsets.UTF_8),
    )
}
