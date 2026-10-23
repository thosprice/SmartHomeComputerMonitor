/**
 * EventGhost Bridge
 *
 * Virtual device representing a computer's awake/asleep state, talking to
 * EventGhost directly via HTTP GET calls to its built-in webserver plugin
 * (no "Send Events to EventGhost" community app required).
 *
 * Write-path design (each command owns exactly one meaning):
 *   on()        User/automation-initiated wake. Optimistically sets switch=on,
 *               sends Wake-on-LAN, AND tells EventGhost to cancel any pending
 *               sleep timer (no-op on EG's side if no sleep was pending --
 *               EG's cancel task only runs while its own timer is armed).
 *               This makes an accidental dashboard tap immediately reversible.
 *   off()       User/automation-initiated sleep request. Optimistically sets
 *               switch=off AND asks EventGhost to sleep the PC.
 *   heartbeat() Called BY EventGhost (routinely, and in reply to ping()).
 *               Updates lastSeen. Self-corrects switch to "on" if it was
 *               stale, but never sends WoL -- this only confirms the PC is
 *               already awake, it doesn't request that it become awake.
 *   ping()      Fire-and-forget outbound nudge asking EventGhost to call
 *               heartbeat() back. Generates NO device event of its own --
 *               there's nothing to trap/reset, which is what avoids the
 *               dimmer-style tail-chase. Optionally carries HE's expected
 *               heartbeat interval (minutes) as a payload, so EG can restart
 *               and synchronize its own timer to match HE. 
 *   triggerAllEvents()
 *               Fires sleep, ping, and cancel-sleep once each, purely so all
 *               three show up in EG's event log to drag-and-drop into new
 *               macros while building/testing. No functional effect beyond that.
 *
 * All scheduling, watchdog, and self-correction logic lives in the
 * companion app (EventGhost Bridge Monitor), not in this driver.
 */
metadata {
    definition(name: "EventGhost Bridge", namespace: "custom", author: "you") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"

        attribute "networkPresence", "string"
        attribute "lastSeen", "number"   // epoch millis of last confirmed heartbeat
        attribute "pcIp", "string"       // mirrors egIp -- EventGhost runs on the PC itself,
                                          // exposed as an attribute so other apps can read it
                                          // without duplicating the value in their own config
        attribute "heartbeatIntervalMinutes", "number"  // mirrors the preference below, so the
                                          // monitor app can read the configured interval directly
                                          // instead of keeping its own separate copy that could drift

        command "heartbeat"              // called by EventGhost
        command "ping", [[name: "intervalOverride", type: "NUMBER", description: "Optional: override the configured heartbeat interval for this ping only, in seconds "]]
        command "confirmAsleep"          // called by the monitor app after verification fails -- fact only, no EG call
        command "triggerAllEvents"       // fires all EG events once, for macro setup
        command "sendNetworkPing"
    }

    preferences {
        input name: "egIp", type: "text", title: "EventGhost host/IPV4", required: true, defaultValue: "0.0.0.0"
        input name: "egPort", type: "number", title: "EventGhost webserver port", defaultValue: 80, required: true
        input name: "sleepEventName", type: "text", title: "EventGhost event name for sleep request", defaultValue: "Hubitat.Sleep", required: true
        input name: "pingEventName", type: "text", title: "EventGhost event name for ping request", defaultValue: "Hubitat.Ping", required: true
        input name: "cancelSleepEventName", type: "text", title: "EventGhost event name to cancel a pending sleep request", defaultValue: "Hubitat.Cancel.Sleep.Request", required: true
        input name: "heartbeatIntervalMinutes", type: "number", title: "Expected heartbeat interval (minutes) -- sent to EG with each ping so it can self-sync", defaultValue: 3, required: true
        input name: "pcMacAddress", type: "text", title: "PC MAC address (for Wake-on-LAN)", required: true, defaultValue: "00:00:00:00:00:00"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    if (device.currentValue("switch") == null) {
        sendEvent(name: "switch", value: "off")
    }
	if(!validIP(egIp)) {
    	log.warn "IP address ${egIp} doesn't look like a valid IPV4 address"
        if(logEnable) log.debug "Network Presence set to 'not present' for ${egIp}"
        sendEvent(name: "networkPresence", value:"not present")
    } else {
	    sendEvent(name: "networkPresence", value: (device.currentValue("switch") == "on" ? "present" : "not present"))
    }

    if (!validMAC(pcMacAddress)) {
        log.warn "MAC address ${pcMacAddress} doesn't look like a valid MAC address. Wake-on-LAN won't work"
    }
    
    sendEvent(name: "pcIp", value: egIp)
    sendEvent(name: "heartbeatIntervalMinutes", value: heartbeatIntervalMinutes)
}

def refresh() {
    // Manual liveness check from the device page.
    ping()
}

def parse(String description) {
    // sendHubCommand()/HubAction routes whatever EventGhost's webserver
    // replies with back here. It's typically just EG's 404 page (expected --
    // see sendEGEvent's comment), so there's nothing to act on; this only
    // exists so Hubitat has a method to call instead of throwing
    // MissingMethodException on every request.
    if (logEnable) {
        try {
            def msg = parseLanMessage(description)
            log.debug "parse(): LAN response received, status=${msg.status ?: 'n/a'}"
        } catch (e) {
            log.debug "parse(): LAN response received (unparsed)"
        }
    }
}

// ---- Commands that express real intent (change what the PC should do) ----

def on() {
    if (logEnable) log.info "on(): optimistic switch=on, sending WoL to ${pcMacAddress}, cancelling any pending sleep"
    sendEvent(name: "switch", value: "on")
    sendWakeOnLan()
    sendEGEvent(cancelSleepEventName)
}

def off() {
    if (logEnable) log.info "off(): optimistic switch=off, requesting sleep via EventGhost"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "networkPresence", value: "not present")
    sendEGEvent(sleepEventName)
}

// ---- Commands driven by EventGhost / the monitor app (no intent, only fact) ----

def confirmAsleep() {
    // Called by the monitor app once it has verified (via ping + optional
    // network check) that no heartbeat is coming. Fact only -- unlike off(),
    // this does NOT send a sleep request to EventGhost, since the PC is
    // presumed already asleep, not being asked to become asleep.
    if (logEnable) log.info "confirmAsleep(): no heartbeat confirmed absent -- marking switch=off (no EG sleep request sent)"
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "networkPresence", value: "not present")
}

def heartbeat() {
    if (logEnable) log.debug "heartbeat() received"
    sendEvent(name: "lastSeen", value: now())
    if (device.currentValue("networkPresence") != "present") {
        sendEvent(name: "networkPresence", value: "present")
    }
    if (device.currentValue("switch") != "on") {
        if (logEnable) log.info "heartbeat() while switch=off -- self-correcting to on (no WoL sent)"
        sendEvent(name: "switch", value: "on")
    }
}

def ping(intervalOverrideSeconds = null) {
    // EG's own timer is configured in seconds, while heartbeatIntervalMinutes
    // (this driver's default, and the app's matching setting) is in minutes
    // for readability on the Hubitat side -- convert at the boundary, right
    // before it leaves the hub, so both sides can use their natural units.
    def effectiveSeconds = intervalOverrideSeconds ?: ((heartbeatIntervalMinutes ?: 3) * 60)
    // Deliberately no sendEvent here. EventGhost's reply arrives later as an
    // ordinary heartbeat() call, on its own attribute, so nothing loops.
    if (logEnable) log.debug "ping(): asking EventGhost to heartbeat back (expected interval: ${effectiveSeconds}s)"
    sendEGEvent(pingEventName, effectiveSeconds as String)
}

def triggerAllEvents() {
    // Fires each EG event once with no functional intent -- purely so all
    // three appear in EG's log to drag-and-drop while building macros.
    if (logEnable) log.info "triggerAllEvents(): firing sleep, ping, and cancel-sleep once each"
    sendEGEvent(sleepEventName)
    sendEGEvent(pingEventName)
    sendEGEvent(cancelSleepEventName)
}

// ---- Helpers ---------------------------------------------------------------
def sendNetworkPing() {
    if(!validIP (egIp)) {
        log.warn "IP address ${egIp} failed pattern check - ping request terminated"
        if(logEnable) log.debug "Network Presence set to 'not present' for ${egIp}"
        sendEvent(name: "networkPresence", value:"not present")
    } else {
        boolean isNewHubFirmware = location.hub.firmwareVersionString > "2.2.6.140"
        if (isNewHubFirmware){
		
            if(logEnable) log.debug "Pinging ${egIp} with hub internal ping method."
            hubitat.helper.NetworkUtils.PingData pingData = hubitat.helper.NetworkUtils.ping(egIp, 1)

            int pTran = pingData.packetsTransmitted.toInteger()
            if (pTran == 0){ // 2.2.7.121 bug returns all zeroes on not found
                pingData.packetsTransmitted = 1
                pingData.packetLoss = 100
            }
            if(logEnable) log.debug "Transmitted: ${pingData.packetsTransmitted}, Received: ${pingData.packetsReceived}, %Lost: ${pingData.packetLoss}"

            def isPresentLabel = (pingData.packetLoss < 100 ? "present" : "not present")
            if(logEnable) log.debug "Network Presence set to '${isPresentLabel}' for ${egIp}"
			sendEvent(name: "networkPresence", value:isPresentLabel)
			
        } else {
		
            if(logEnable) log.debug "Pinging ${egIp} with hub endpoint method."
            asynchttpGet("sendNetworkPingHandler", [ uri: "http://${location.hub.localIP}:8080", path:"/hub/networkTest/ping/"+egIp, ])
        }
    }
    
}

def sendNetworkPingHandler(resp, data) {
    try {
	    if(resp.getStatus() == 200 || resp.getStatus() == 207) {
		    strWork = resp.data.toString()
        } else {
			log.warn "Ping Error - Status ${resp.getStatus()}"
		}
		
		isPresentLabel = pingSuccess(strWork) ? "present" : "not present"

		sendEvent(name:"networkPresence",value:isPresentLabel)
        if(logEnable) log.debug "Network Presence set to '${isPresentLabel}' for ${egIp}"

    } catch(Exception ex) { 
        sendEvent(name:"networkPresence",value:"not present")
        log.warn "sendNetworkPing httpResp = ${resp.getStatus()} but returned invalid data"
    } 

}

def pingSuccess(strWork) {
	int startInx = strWork?.indexOf("%") ?: -1

    if (startInx == -1){
        if(logEnable) log.debug "Invalid response received from endpoint"
        return false
    } else {
        startInx -= 3
        strWork = strWork.substring(startInx)
        
        int startChar = ((strWork.substring(0,1)==",")?1:0)
		percentLoss = strWork.substring(startChar, 3).toInteger()
        
        startInx  = strWork.indexOf("=")
        pingStats = strWork.substring(startInx+2,strWork.length()-4).tokenize("/")
        if(logEnable) log.debug "Ping Stats: ${pingStats}"
		
		return (percentLoss < 100 ? true : false)
    }
}
       
def validIP(ipAddress){
    regxPattern =/^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/
    return ipAddress != null && ipAddress ==~ regxPattern
}

def validMAC(macAddress) {
    // Supports XX:XX:XX:XX:XX:XX, XX-XX-XX-XX-XX-XX, or XXXX.XXXX.XXXX
    regxPattern = /^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$|^([0-9A-Fa-f]{4}\.){2}([0-9A-Fa-f]{4})$/
    return macAddress != null && macAddress ==~ regxPattern
}


private sendEGEvent(String eventName, String payload = null) {
    // httpGet()'s URI/query-string builder appears to silently drop a bare
    // "&value" segment that isn't a "key=value" pair (event name arrives,
    // payload doesn't). Sending a raw crafted GET via HubAction avoids that
    // parsing layer entirely -- this mirrors the community "Send Events to
    // EventGhost" app's proven-working request format exactly.
    def egHost = "${egIp}:${egPort}"
    def encodedEvent = java.net.URLEncoder.encode(eventName, "UTF-8")
    def command = encodedEvent
    if (payload) {
        command += "&" + java.net.URLEncoder.encode(payload, "UTF-8")
    }
    try {
        sendHubCommand(new hubitat.device.HubAction(
            "GET /?${command} HTTP/1.1\r\nHOST: ${egHost}\r\n\r\n",
            hubitat.device.Protocol.LAN
        ))
        if (logEnable) log.debug "sendEGEvent(${eventName}) sent via HubAction: ${command}"
    } catch (e) {
        log.warn "sendEGEvent(${eventName}) failed: ${e.message}"
    }
}

private sendWakeOnLan() {
    try {
        def macClean = pcMacAddress?.replaceAll(/[-:.]/, "").toUpperCase()
	    def command = "FFFFFFFFFFFF$macClean$macClean$macClean$macClean$macClean$macClean$macClean$macClean" +
            "$macClean$macClean$macClean$macClean$macClean$macClean$macClean$macClean"
    	def wol = new hubitat.device.HubAction(command, 
       		hubitat.device.Protocol.LAN, 
       		[type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, 
        	destinationAddress: "255.255.255.255:9",
        	encoding: hubitat.device.HubAction.Encoding.HEX_STRING])
        sendHubCommand(wol)
    } catch (e) {
        log.warn "sendWakeOnLan failed: ${e.message}"
    }
}
