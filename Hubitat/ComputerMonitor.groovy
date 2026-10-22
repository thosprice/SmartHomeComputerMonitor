/**
 * Computer Monitor app, via EventGhost or scripting
 *
 * Owns all timing/state-machine logic for an "EventGhost Bridge" device:
 *
 *  - switch -> on   : arm the full watchdog (missed heartbeat = unexpected).
 *  - switch -> off  : arm a simple confirm-sleep window (missed heartbeat =
 *                      expected outcome; a heartbeat arriving instead means
 *                      EventGhost's own cancel/guard blocked the sleep).
 *  - lastSeen event : always means "resume/confirm monitor mode" -- covers
 *                      routine heartbeats, self-correction after hub reboot,
 *                      and the EG-guard-cancelled-sleep case, with one rule.
 *  - watchdog fires while monitoring : verify via ping() before alerting --
 *                      EG-level check first, then an optional network-level
 *                      check, to distinguish "EventGhost hung" from
 *                      "PC actually unreachable."
 * These commands are available through MakerAPI and called by EG with simple HTTP
 * GET requests.  Background scripts on other systems can replicate EG on
 * linux (confirmed) or Mac (probably)
 */
definition(
    name: "Computer Monitor",
    namespace: "tprice-custom",
    author: "tprice",
    parent: 'tprice-custom:Computer Monitor Parent',
    description: "Heartbeat watchdog and self-correction for the EventGhost/scripting PC bridge device",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    iconX3Url: "",
    singleInstance: false
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Computer Monitor", install: true, uninstall: true) {
        section("<b>Device:</b>") {
            paragraph "&nbsp;", width: 1
            input name: "bridgeDevice", type: "capability.switch",
                title: "EventGhost/scripting Bridge device to monitor", required: true, multiple: false, width:10
            paragraph "&nbsp;", width: 1
        }
        section("<b>Timing:</b>") {
            paragraph "The bridge device sends a periodic heartbeat to let this app know it's awake. " +
                "The heartbeat interval is set in the bridge device's own preferences and mirrored here."
            paragraph "&nbsp;", width: 1
            input name: "graceMinutes", type: "number",
                title: "Grace period after missed ping before starting computer off confirmation (minutes)", defaultValue: 1, required: true, width: 4
            paragraph "&nbsp;", width: 1
            input name: "pingWaitSeconds", type: "number",
                title: "Time to wait for a reply after EventGhost ping() to confirm computer off (seconds)", defaultValue: 20, required: true, width: 4
            paragraph "&nbsp;", width: 2
        }
        section("<b>Optional network-level check:</b>") {
            paragraph "&nbsp;", width: 1
            input name: "enableNetworkCheck", type: "bool",
                title: "Also check network-level reachability when computer doesn't respond to Event Ghost ping", defaultValue: false, width: 9
            paragraph "&nbsp;", width: 2
        }
        section("<b>Notifications:</b>") {
            paragraph "&nbsp;", width: 1
			paragraph "Notifications are sent if device or heartbeat script seem to have crashed.", width: 10
            paragraph "&nbsp;", width: 1
            paragraph "&nbsp;", width: 1
            input name: "notifyDevice", type: "capability.notification",
                title: "Send a notification to", required: false, multiple: true, width: 10
            paragraph "&nbsp;", width: 1
            paragraph "&nbsp;", width: 1
            input name: 'testNotificationButton', type: 'button', title: '<b>Send a test notification</b>', width: 5
            paragraph "&nbsp;", width: 6
        }
        section() {
            input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
			label title: "Instance name for this app", required: false, width: 6
            paragraph "&nbsp;", width: 6
        }
        section("<b>Instructions:</b>") {
            instructionsDisplayActive = state.showInstructions

            input name: 'instructionsButton', type: 'button', 
                title: "Instructions " + (instructionsDisplayActive ? "\u25b2 Collapse" : "\u25bc Expand"), submitOnChange: true

            if (instructionsDisplayActive) {
                paragraph "&nbsp;", width: 1
                paragraph "This app works with the bridging device and a script application (like Windows' EventGhost) " +
                    "to let the hub show a computer's wake status and to send wake and sleep commands.  It has been implemented on Windows and " +
                    "linux systems.", width: 9
                paragraph "&nbsp;", width: 2
                paragraph "&nbsp;", width: 1
                paragraph "EventGhost/script needs to have the following functions: 1) Send a heartbeat ping to the bridge device via MakerAPI " +
                    "when waking and on a set schedule. 2) Respond to an EventGhost ping " +
                    "event (named in the bridge device preferences) with a heartbeat. The optional EG ping payload is the expected heartbeat " +
                    "interval, so EG/script can synchronize its timer automatically in case of a discrepancy. 3) Respond to a sleep event " +
                    "(also named in bridge device preferences) by initiating computer sleep. You can have this trigger a cancellable timer on the " +
                    "computer to prevent accidentally forcing a sleep state.", width: 9
                paragraph "&nbsp;", width: 2
                paragraph "&nbsp;", width: 1
                paragraph "This app maintains on/off for the bridge device on the hub by listening for the heartbeat. The hub device is switched " +
                    "on when a heartbeat is received. If a heartbeat is missed, the app pings the computer via EG (or script) " +
                    "seeking a response. If there is none, an optional network ping is attempted, after which the hub device is switched off.", width: 9
                paragraph "&nbsp;", width: 2
                paragraph "&nbsp;", width: 1
                paragraph "If the hub device switch is toggled, switching to on triggers a wake-on-lan ping to the computer, while switching " +
                    "to off sends a sleep " +
                    "request to the computer via EG/script. ", width: 9
                paragraph "&nbsp;", width: 2
            }
            
        }
    }
}

def installed() { 
    state.showInstructions = true
    initialize() 
}

def updated() {
    log.debug "Updating . . ."
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    subscribe(bridgeDevice, "switch", switchHandler)
    subscribe(bridgeDevice, "lastSeen", lastSeenHandler)

    // Hub-reboot resync: don't trust a stale "on" -- give it one window to
    // prove itself via a real heartbeat, same as any other monitor cycle.
    if (bridgeDevice.currentValue("switch") == "on") {
        atomicState.mode = "monitor"
        rearm()
    } else {
        atomicState.mode = "idle"
    }
}

// Called by the platform whenever a button input on the page is pressed
void appButtonHandler(String btn) {
    if (btn == 'instructionsButton') {
        Boolean current = state.showInstructions
        state.showInstructions = !current
    } else if (btn == 'testNotificationButton') {
        notify('Computer Monitor test notification')
    }
}

// ---- Attribute event handlers ---------------------------------------------

def switchHandler(evt) {
    if (evt.value == "on") {
        if (logEnable) log.debug "switch -> on: arming monitor watchdog"
        atomicState.mode = "monitor"
    } else {
        if (logEnable) log.debug "switch -> off: arming confirm-sleep window"
        atomicState.mode = "confirmOff"
    }
    rearm()
}

def lastSeenHandler(evt) {
    if (logEnable) log.debug "lastSeen updated: (re)confirming monitor mode"
    // A heartbeat always means "monitor" -- covers routine beats, EG's
    // cancel-sleep guard firing during a confirmOff window, and post-reboot
    // self-correction, with a single rule.
    atomicState.mode = "monitor"
    rearm()
}

// ---- Scheduling -------------------------------------------------------------

private effectiveIntervalMinutes() {
    bridgeDevice.currentValue("heartbeatIntervalMinutes") ?: 3
}

private rearm() {
    unschedule("watchdogFired")
    Integer secs = (effectiveIntervalMinutes() + (graceMinutes ?: 1)) * 60
    runIn(secs, "watchdogFired")
}

def watchdogFired() {
    if (atomicState.mode == "confirmOff") {
        if (logEnable) log.info "No heartbeat during confirm window -- sleep confirmed."
        atomicState.mode = "idle"
        return
    }
    if (logEnable) log.info "Missed heartbeat while monitoring -- verifying before alerting."
    verifyAndMaybeNotify()
}

// ---- Verification chain (only reached on an UNEXPECTED missed heartbeat) ----

private verifyAndMaybeNotify() {
    atomicState.lastSeenBeforeEGPing = bridgeDevice.currentValue("lastSeen")
    bridgeDevice.ping()  // no override needed: driver's own preference is the same value we just read
    runIn((pingWaitSeconds ?: 20), "checkPingReply")
}

def checkPingReply() {
    def current = bridgeDevice.currentValue("lastSeen")
    if (current != atomicState.lastSeenBeforeEGPing) {
        if (logEnable) log.info "Computer replied to EventGhost ping -- false alarm, resuming normal monitoring."
        rearm()
        return
    }

    if (logEnable) log.warn "No reply to EventGhost ping() from computer."

    if (enableNetworkCheck) {
        def ip = bridgeDevice.currentValue("pcIp")
        if (ip) {
            bridgeDevice.sendNetworkPing()
            runIn(10, "checkNetworkPingReply")
        } else {
            log.warn "Network check enabled but bridge device has no pcIp attribute value -- check computer's host/IP preference is set."
            bridgeDevice.confirmAsleep()
            notify("Script is not responding and PC IP wasn't available to verify further -- marking PC asleep.")
        }
    } else {
        // No network-level check available: a missed heartbeat is treated as sufficient evidence on its own.
        bridgeDevice.confirmAsleep()
        notify("No heartbeat and no network ping enabled -- marking PC asleep.")
    }
    // No rearm() here in the confirmAsleep paths -- confirmAsleep() flips
    // switch to off, which switchHandler picks up and arms the confirmOff
    // window itself. Calling rearm() here too would race with that.
}

def checkNetworkPingReply() {
    def reachable = bridgeDevice.currentValue("networkPresence") == "present"
    if (reachable) {
        // PC is on the network but EG isn't answering -- likely a hung/crashed
        // EG process, not a sleeping PC. Don't declare off; keep watching.
        notify("PC is reachable on the network, but computer is not responding. Likely an EventGhost/script hang or crash.")
        rearm()
    } else {
        bridgeDevice.confirmAsleep()
        notify("PC appears unreachable on the network (asleep, off, or network issue), and no ping response -- marking PC asleep.")
        // No rearm() -- switchHandler arms the confirmOff window when switch flips to off.
    }
}

// ---- Notification helper ----------------------------------------------------

private notify(String message) {
    log.warn message
    if (!notifyDevice) log.warn 'No notification device set'
    
    notifyDevice?.each { it.deviceNotification(message) }
}
