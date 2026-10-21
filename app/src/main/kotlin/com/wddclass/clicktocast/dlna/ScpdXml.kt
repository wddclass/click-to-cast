package com.wddclass.clicktocast.dlna

/** Minimal but valid service descriptions so controllers that fetch SCPD can call us. */
object ScpdXml {
    fun avTransport(): String = document(
        actions = listOf(
            action("SetAVTransportURI", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("CurrentURI", "AVTransportURI"), inn("CurrentURIMetaData", "AVTransportURIMetaData")),
            action("SetNextAVTransportURI", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("NextURI", "NextAVTransportURI"), inn("NextURIMetaData", "NextAVTransportURIMetaData")),
            action("GetMediaInfo", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("NrTracks", "NumberOfTracks"), out("MediaDuration", "CurrentMediaDuration"), out("CurrentURI", "AVTransportURI"), out("CurrentURIMetaData", "AVTransportURIMetaData"), out("NextURI", "NextAVTransportURI"), out("NextURIMetaData", "NextAVTransportURIMetaData"), out("PlayMedium", "PlaybackStorageMedium"), out("RecordMedium", "RecordStorageMedium"), out("WriteStatus", "RecordMediumWriteStatus")),
            action("GetTransportInfo", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("CurrentTransportState", "TransportState"), out("CurrentTransportStatus", "TransportStatus"), out("CurrentSpeed", "TransportPlaySpeed")),
            action("GetPositionInfo", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("Track", "CurrentTrack"), out("TrackDuration", "CurrentTrackDuration"), out("TrackMetaData", "CurrentTrackMetaData"), out("TrackURI", "CurrentTrackURI"), out("RelTime", "RelativeTimePosition"), out("AbsTime", "AbsoluteTimePosition"), out("RelCount", "RelativeCounterPosition"), out("AbsCount", "AbsoluteCounterPosition")),
            action("GetDeviceCapabilities", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("PlayMedia", "PossiblePlaybackStorageMedia"), out("RecMedia", "PossibleRecordStorageMedia"), out("RecQualityModes", "PossibleRecordQualityModes")),
            action("GetTransportSettings", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("PlayMode", "CurrentPlayMode"), out("RecQualityMode", "CurrentRecordQualityMode")),
            action("Stop", inn("InstanceID", "A_ARG_TYPE_InstanceID")),
            action("Play", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("Speed", "TransportPlaySpeed")),
            action("Pause", inn("InstanceID", "A_ARG_TYPE_InstanceID")),
            action("Seek", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("Unit", "A_ARG_TYPE_SeekMode"), inn("Target", "A_ARG_TYPE_SeekTarget")),
            action("GetCurrentTransportActions", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("Actions", "CurrentTransportActions")),
        ),
        variables = listOf(
            variable("TransportState", events = true),
            variable("TransportStatus", events = true),
            variable("PlaybackStorageMedium"),
            variable("RecordStorageMedium"),
            variable("PossiblePlaybackStorageMedia"),
            variable("PossibleRecordStorageMedia"),
            variable("CurrentPlayMode"),
            variable("TransportPlaySpeed"),
            variable("RecordMediumWriteStatus"),
            variable("CurrentRecordQualityMode"),
            variable("PossibleRecordQualityModes"),
            variable("NumberOfTracks"),
            variable("CurrentTrack"),
            variable("CurrentTrackDuration"),
            variable("CurrentMediaDuration"),
            variable("CurrentTrackMetaData"),
            variable("CurrentTrackURI"),
            variable("AVTransportURI"),
            variable("AVTransportURIMetaData"),
            variable("NextAVTransportURI"),
            variable("NextAVTransportURIMetaData"),
            variable("RelativeTimePosition"),
            variable("AbsoluteTimePosition"),
            variable("RelativeCounterPosition"),
            variable("AbsoluteCounterPosition"),
            variable("CurrentTransportActions"),
            variable("LastChange", events = true),
            variable("A_ARG_TYPE_SeekMode"),
            variable("A_ARG_TYPE_SeekTarget"),
            variable("A_ARG_TYPE_InstanceID"),
        ),
    )

    fun rendering(): String = document(
        actions = listOf(
            action("ListPresets", inn("InstanceID", "A_ARG_TYPE_InstanceID"), out("CurrentPresetNameList", "PresetNameList")),
            action("SelectPreset", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("PresetName", "A_ARG_TYPE_PresetName")),
            action("GetMute", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("Channel", "A_ARG_TYPE_Channel"), out("CurrentMute", "Mute")),
            action("SetMute", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("Channel", "A_ARG_TYPE_Channel"), inn("DesiredMute", "Mute")),
            action("GetVolume", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("Channel", "A_ARG_TYPE_Channel"), out("CurrentVolume", "Volume")),
            action("SetVolume", inn("InstanceID", "A_ARG_TYPE_InstanceID"), inn("Channel", "A_ARG_TYPE_Channel"), inn("DesiredVolume", "Volume")),
        ),
        variables = listOf(
            variable("Mute", events = true),
            variable("Volume", events = true),
            variable("PresetNameList", events = true),
            variable("LastChange", events = true),
            variable("A_ARG_TYPE_Channel"),
            variable("A_ARG_TYPE_InstanceID"),
            variable("A_ARG_TYPE_PresetName"),
        ),
    )

    fun connection(): String = document(
        actions = listOf(
            action("GetProtocolInfo", out("Source", "SourceProtocolInfo"), out("Sink", "SinkProtocolInfo")),
            action("GetCurrentConnectionIDs", out("ConnectionIDs", "CurrentConnectionIDs")),
            action("GetCurrentConnectionInfo", inn("ConnectionID", "A_ARG_TYPE_ConnectionID"), out("RcsID", "A_ARG_TYPE_RcsID"), out("AVTransportID", "A_ARG_TYPE_AVTransportID"), out("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), out("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), out("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), out("Direction", "A_ARG_TYPE_Direction"), out("Status", "A_ARG_TYPE_ConnectionStatus")),
        ),
        variables = listOf(
            variable("SourceProtocolInfo", events = true),
            variable("SinkProtocolInfo", events = true),
            variable("CurrentConnectionIDs", events = true),
            variable("A_ARG_TYPE_ConnectionStatus"),
            variable("A_ARG_TYPE_ConnectionManager"),
            variable("A_ARG_TYPE_Direction"),
            variable("A_ARG_TYPE_ProtocolInfo"),
            variable("A_ARG_TYPE_ConnectionID"),
            variable("A_ARG_TYPE_AVTransportID"),
            variable("A_ARG_TYPE_RcsID"),
        ),
    )

    private data class Arg(val name: String, val direction: String, val variable: String)

    private fun inn(name: String, variable: String) = Arg(name, "in", variable)
    private fun out(name: String, variable: String) = Arg(name, "out", variable)

    private fun action(name: String, vararg args: Arg): String = buildString {
        append("<action><name>").append(name).append("</name>")
        if (args.isNotEmpty()) {
            append("<argumentList>")
            for (arg in args) {
                append("<argument><name>").append(arg.name).append("</name><direction>")
                append(arg.direction).append("</direction><relatedStateVariable>")
                append(arg.variable).append("</relatedStateVariable></argument>")
            }
            append("</argumentList>")
        }
        append("</action>")
    }

    private fun variable(name: String, events: Boolean = false): String =
        """<stateVariable sendEvents="${if (events) "yes" else "no"}"><name>$name</name><dataType>string</dataType></stateVariable>"""

    private fun document(actions: List<String>, variables: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>""")
        append("<actionList>")
        actions.forEach { append(it) }
        append("</actionList><serviceStateTable>")
        variables.forEach { append(it) }
        append("</serviceStateTable></scpd>")
    }
}
