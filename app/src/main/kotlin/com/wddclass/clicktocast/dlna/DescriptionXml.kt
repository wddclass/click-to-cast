package com.wddclass.clicktocast.dlna

object DescriptionXml {
    fun device(profile: DeviceProfile): String {
        val name = XmlText.escape(
            profile.friendlyName.trim().take(64).ifBlank { Upnp.DEFAULT_FRIENDLY_NAME },
        )
        val uuid = XmlText.escape(profile.uuid)
        val version = XmlText.escape(profile.version)
        val base = XmlText.escape(profile.urlBase)
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>$base</URLBase>
  <device>
    <deviceType>${Upnp.DEVICE}</deviceType>
    <friendlyName>$name</friendlyName>
    <manufacturer>wddclass</manufacturer>
    <manufacturerURL>https://github.com/wddclass/click-to-cast</manufacturerURL>
    <modelDescription>DLNA media renderer</modelDescription>
    <modelName>投屏点我</modelName>
    <modelNumber>$version</modelNumber>
    <modelURL>https://github.com/wddclass/click-to-cast</modelURL>
    <serialNumber>$uuid</serialNumber>
    <UDN>uuid:$uuid</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>${Upnp.AVT}</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>${DmrPaths.SCPD_AVT}</SCPDURL>
        <controlURL>${DmrPaths.CONTROL_AVT}</controlURL>
        <eventSubURL>${DmrPaths.EVENT_AVT}</eventSubURL>
      </service>
      <service>
        <serviceType>${Upnp.RCS}</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>${DmrPaths.SCPD_RCS}</SCPDURL>
        <controlURL>${DmrPaths.CONTROL_RCS}</controlURL>
        <eventSubURL>${DmrPaths.EVENT_RCS}</eventSubURL>
      </service>
      <service>
        <serviceType>${Upnp.CMS}</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>${DmrPaths.SCPD_CMS}</SCPDURL>
        <controlURL>${DmrPaths.CONTROL_CMS}</controlURL>
        <eventSubURL>${DmrPaths.EVENT_CMS}</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>
"""
    }
}
