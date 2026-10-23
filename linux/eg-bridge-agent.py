#!/usr/bin/env python3
"""
EventGhost Bridge Agent (Linux Mint)

Replaces EventGhost's role for the "EventGhost Bridge" Hubitat driver:

  - Listens for the same raw "GET /?EventName&Payload HTTP/1.1" requests the
    driver already sends (matching the community-app-derived format --
    NOT a normal query string, no "=" between name and value).
  - Hubitat.Ping&<seconds>   -> immediately calls heartbeat() via Maker API,
                                 and syncs the local heartbeat timer to the
                                 given interval if it differs.
  - Hubitat.Sleep            -> arms a cancellable delayed suspend (gives a
                                 window to cancel an accidental/buggy request,
                                 same purpose as your EG Cancel Timer task).
  - Hubitat.Cancel.Sleep.Request -> cancels the pending suspend if one is
                                 armed; no-op otherwise.
  - A background thread calls heartbeat() via Maker API on a timer.
  - A logind DBus listener calls heartbeat() immediately on resume from
    suspend, replacing your system.resumeautomatic trap.

REQUIRES: python3-dbus (for resume detection). Install with:
    sudo apt install python3-dbus

CONFIGURE the constants below before running.
"""

import http.server
import threading
import time
import subprocess
import urllib.request
import urllib.parse
import logging
import sys
import os

# ---- Configuration ----------------------------------------------------------

HUB_IP = "###.###.###.###"           # Hubitat hub IP
MAKER_API_APP_ID = "REPLACE_ME"     # Maker API app id (from the app's URL/page)
DEVICE_ID = "REPLACE_ME"                  # EventGhost Bridge device id
ACCESS_TOKEN = "REPLACE_ME"         # Maker API access token

LISTEN_PORT = 80                    # Must match the driver's "EventGhost webserver port"
                                     # preference. Ports < 1024 require running as root
                                     # (or use setcap/authbind) -- see notes below.

DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 180  # Overridden automatically if a
                                            # Hubitat.Ping payload arrives with
                                            # a different value (mirrors EG's
                                            # TimerAction sync behavior).

SLEEP_DELAY_SECONDS = 10             # Window during which Hubitat.Cancel.Sleep.Request
                                      # can abort a pending sleep. Matches the
                                      # "cancellable timer" pattern from EG.

EVENT_NAMES = {
    "ping": "Hubitat.Ping",
    "sleep": "Hubitat.Sleep",
    "cancel_sleep": "Hubitat.Cancel.Sleep.Request",
}

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("eg-bridge-agent")

# ---- Maker API -----------------------------------------------------------------

def call_maker_api(command, value=None):
    """Fire-and-forget call to a Maker API device command."""
    path = f"/apps/api/{MAKER_API_APP_ID}/devices/{DEVICE_ID}/{command}"
    if value is not None:
        path += f"/{value}"
    url = f"http://{HUB_IP}{path}?access_token={ACCESS_TOKEN}"
    try:
        with urllib.request.urlopen(url, timeout=5) as resp:
            log.debug("Maker API %s -> HTTP %s", command, resp.status)
    except Exception as e:
        log.warning("Maker API call %s failed: %s", command, e)


def send_heartbeat():
    call_maker_api("heartbeat")


# ---- Heartbeat timer (interruptible/resettable, like the driver's rearm()) -----

class HeartbeatTimer:
    def __init__(self, interval_seconds):
        self.interval = interval_seconds
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self._thread.start()

    def _run(self):
        while not self._stop.is_set():
            send_heartbeat()
            log.info("Heartbeat sent (interval=%ss)", self.interval)
            self._stop.wait(self.interval)

    def set_interval(self, new_interval_seconds):
        if new_interval_seconds and new_interval_seconds != self.interval:
            log.info("Syncing heartbeat interval: %ss -> %ss", self.interval, new_interval_seconds)
            self.interval = new_interval_seconds
            # Wake the wait() early so the new interval takes effect promptly
            self._stop.set()
            self._stop.clear()

    def fire_now(self):
        send_heartbeat()


heartbeat_timer = HeartbeatTimer(DEFAULT_HEARTBEAT_INTERVAL_SECONDS)

def notify(title, message):
    """Desktop toast via notify-send. User systemd services don't always
    inherit DBUS_SESSION_BUS_ADDRESS reliably, so set it explicitly to the
    standard well-known path rather than relying on inheritance."""
    env = os.environ.copy()
    env.setdefault("DBUS_SESSION_BUS_ADDRESS", f"unix:path=/run/user/{os.getuid()}/bus")
    try:
        subprocess.run(["notify-send", title, message], env=env, check=False)
    except FileNotFoundError:
        log.warning("notify-send not found -- install with: sudo apt install libnotify-bin")


# ---- Cancellable delayed suspend ------------------------------------------------

_pending_sleep_timer = None
_pending_sleep_lock = threading.Lock()


def do_suspend():
    log.info("Delay elapsed with no cancellation -- suspending now.")
    subprocess.run(["systemctl", "suspend"], check=False)


def handle_sleep_request():
    global _pending_sleep_timer
    with _pending_sleep_lock:
        if _pending_sleep_timer and _pending_sleep_timer.is_alive():
            log.info("Sleep already pending -- ignoring duplicate request.")
            return
        log.info("Sleep requested -- suspending in %ss unless cancelled.", SLEEP_DELAY_SECONDS)
        notify("Sleep Requested", f"System will suspend in {SLEEP_DELAY_SECONDS}s unless cancelled.")
        _pending_sleep_timer = threading.Timer(SLEEP_DELAY_SECONDS, do_suspend)
        _pending_sleep_timer.daemon = True
        _pending_sleep_timer.start()


def handle_cancel_sleep_request():
    global _pending_sleep_timer
    with _pending_sleep_lock:
        if _pending_sleep_timer and _pending_sleep_timer.is_alive():
            _pending_sleep_timer.cancel()
            _pending_sleep_timer = None
            log.info("Pending sleep cancelled.")
            notify("Sleep Cancelled", "The pending sleep request was cancelled.")
        else:
            log.debug("Cancel-sleep received with no pending sleep -- no-op.")


# ---- HTTP listener (matches the driver's raw "?Event&Payload" format) ----------

class EventHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        # self.path looks like "/?Hubitat.Ping&180" -- NOT standard query
        # string format (no "="), so parse it manually rather than with
        # urllib.parse.parse_qs, which expects key=value pairs.
        raw = self.path.lstrip("/?")
        parts = raw.split("&", 1)
        event_name = urllib.parse.unquote(parts[0]) if parts else ""
        payload = urllib.parse.unquote(parts[1]) if len(parts) > 1 else None

        log.info("Received event: %s payload=%s", event_name, payload)

        if event_name == EVENT_NAMES["ping"]:
            heartbeat_timer.fire_now()
            if payload:
                try:
                    heartbeat_timer.set_interval(int(float(payload)))
                except ValueError:
                    log.warning("Ping payload wasn't numeric: %r", payload)
        elif event_name == EVENT_NAMES["sleep"]:
            handle_sleep_request()
        elif event_name == EVENT_NAMES["cancel_sleep"]:
            handle_cancel_sleep_request()
        else:
            log.debug("Unrecognized event, ignoring: %s", event_name)

        # Reply 404 deliberately -- mirrors EventGhost's own webserver plugin
        # behavior, which the Hubitat driver already treats as expected/normal.
        self.send_response(404)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(b"Not Found")

    def log_message(self, format, *args):
        pass  # suppress default request logging; we log explicitly above


def run_http_server():
    server = http.server.HTTPServer(("0.0.0.0", LISTEN_PORT), EventHandler)
    log.info("Listening on port %s", LISTEN_PORT)
    server.serve_forever()


# ---- Resume detection via logind DBus (replaces system.resumeautomatic) --------

def watch_for_resume():
    try:
        import dbus
        from dbus.mainloop.glib import DBusGMainLoop
        from gi.repository import GLib
    except ImportError:
        log.warning(
            "python3-dbus / python3-gi not available -- resume detection disabled. "
            "Install with: sudo apt install python3-dbus python3-gi"
        )
        return

    def on_prepare_for_sleep(sleeping):
        if not sleeping:
            log.info("Resume detected via logind -- sending immediate heartbeat.")
            heartbeat_timer.fire_now()

    DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    bus.add_signal_receiver(
        on_prepare_for_sleep,
        signal_name="PrepareForSleep",
        dbus_interface="org.freedesktop.login1.Manager",
    )
    log.info("Watching logind for resume events.")
    GLib.MainLoop().run()


# ---- Entry point ------------------------------------------------------------------

def main():
    if MAKER_API_APP_ID == "REPLACE_ME" or ACCESS_TOKEN == "REPLACE_ME":
        log.error("Set MAKER_API_APP_ID and ACCESS_TOKEN before running.")
        sys.exit(1)

    heartbeat_timer.start()

    dbus_thread = threading.Thread(target=watch_for_resume, daemon=True)
    dbus_thread.start()

    run_http_server()  # blocks


if __name__ == "__main__":
    main()
