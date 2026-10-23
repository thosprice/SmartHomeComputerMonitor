Sample W11 / Eventghost implementation.

Files to implement in Linux.  Developed for Mint / XCFE. 

Both the computer and hub require a static IP or DHCP reservation on the router.

See the Hubitat folder readme for Hubitat instructions.

On the computer:

You can load the xml file into EventGhost after installation.  The file can be saved anywhere and loaded from the downloads folder,
but you may want to create a folder for it.  

EventGhost configuration includes the following steps on the Windows machine.  

Use the MakerAPI APP_ID and ACCESS_TOKEN and the bridge device DEVICE_ID to assign the eg.globals.target_url variable in EventGhost Autostart -> URL for Hubitat HTTP GET requests.  MakerAPI APP_ID and ACCESS_TOKEN can be found in the sample URLs at the bottom of the page. DEVICE_ID for the paired EventGhost Bridge device appears in the URL window when the device is open in its own tab, or as the first test in the devices log entries. Double-click the action to configure it. The URL format is "http://hubitat.local/apps/api/APP_ID/devices/DEVICE_ID/heartbeat?access_token=ACCESS_TOKEN"'

Double-click the Webserver plugin and make sure it is using the same port as is set in the HE computer bridge device preferences.

EventGhost will receive events from the hub, sent to the computer's IP.  It is possible that a firewall or antivirus software
will block the incoming HTTP.  You may need to whitelist a port so EG can receive the packets.

EventGhost will automatically send heartbeat pings to Hubitat, will respond to ping requests, and will respond to sleep requests by suspending the machine.  Sleep requests can be cancelled during a specified interval (default 45s) by pressing the configured hotkey (default Ctrl + Win).

If you want an on toggle from HE to wake the computer, enable Wake-on-LAN on your Windows computer.

1. Configure the Network Adapter

Start Device Manager as Administrator from the Windows menu and expand Network adapters.  Identify the Ethernet card, right-click, and choose Properties.  
Under the Power Management tab, check:
	• Allow the computer to turn off this device to save power
	• Allow this device to wake the computer
	• Only allow a magic packet to wake the computer
Under the Advanced tab, set Wake on Magic Packet to Enabled.
(Optional) Disable Energy-Efficient Ethernet or low-power modes if listed.

2. Disable Fast Startup

Start Control Panel as Administrator from the Windows menu and go to Hardware and Sound > Power Options. Select Choose what the power buttons do on the left menu -> Change settings that are currently unavailable -> Uncheck Turn on fast startup (recommended), then save changes

3. Enable Wake on LAN in the BIOS/UEFI. Different motherboard manufacturers label this differently; look for Wake on LAN, Resume on LAN, or Power On By PCI-E or similar and set to Enabled.

Final checks:

Once both devices are set up, a heartbeat should appear on Hubitat about every 3 minutes (or custom heartbeat interval) when the computer is awake.
The Ping button on the bridge device page in Hubitat should receive a heartbeat reply.
off() from the hubitat device should show the toast (on screen display) on the computer and then suspend if not cancelled.  off() from the hubitat device followed by on of the cancellation shortcut should cancel the suspend.
After suspending the computer, then resuming, a heartbeat should arrive at Hubitat promptly.
on() from the hubitat device should wake the computer via WoL.
After a reboot, EventGhost starts on its own (you may need to set this in EG preferences), and heartbeats should start arriving at Hubitat.

The access token is a secret, so keep it out of any shared copy of the docs.
Toasts appear only in the account that owns the service. A different account that’s active won’t see them, though the machine still suspends.
