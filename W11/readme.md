Sample W11 / Eventghost implementation.

Files to implement in Linux.  Developed for Mint / XCFE. 

Both the computer and hub require a static IP or DHCP reservation on the router.

See the Hubitat folder readme for Hubitat instructions.

On the computer:

You can load the xml file into EventGhost after installation.  The file can be saved anywhere and loaded from the downloads folder,
but you may want to create a folder for it.  

EventGhost configuration includes the following steps on the Windows machine.  

Use these MakerAPI APP_ID and ACCESS_TOKEN and the bridge device DEVICE_ID to create the eg.globals.target_url variable in EventGhost Autostart.  Double-click the action to configure it.

Double-click the Webserver plugin and make sure it is using the same port set in the HE computer bridge device preferences.

EventGhost will receive events from the hub, sent to the computer's IP.  It is possible that a firewall or antivirus software
will block the incoming HTTP.  You may need to whitelist a port so EG can receive the packets.

EventGhost will automatically send heartbeat pings to Hubitat, will respond to ping requests, and will respond to sleep requests by suspending the machine.  Sleep requests can be cancelled during a specified interval by pressing the configured hotkey, set by default to 45 seconds and Ctrl + Win Key

Enable Wake-on-LAN on your Windows computer.

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

Once both devices are set up, a heartbeat should appears on Hubitat about every 3 minutes when the computer is awake.
The Ping button on the bridge device page in Hubitat gets a heartbeat reply.
off() from the hubitat device shows the toast on the computer and then suspends if not cancelled.  off() from the hubitat device followed by on of the cancellation shortcut cancels the suspend.
After suspending the computer, then resuming, a heartbeat arrives at Hubitat promptly.
on() from the hubitat device wakes the computer via WoL.
After a reboot, the service starts on its own with nobody logged in, and heartbeats start arriving at Hubitat.

The access token is a secret, so keep it out of any shared copy of the docs.
Toasts appear only in the account that owns the service. A different account that’s active won’t see them, though the machine still suspends.
