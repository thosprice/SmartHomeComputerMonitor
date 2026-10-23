These are the two app drivers and device driver to implement computer state tracking on HE.

Install the parent app code for Computer Monitor first, then the child app code, then the EventGhostBridge device driver code.

Installation instructions for setting up your Linux or W11 computer can be found in those folders' readme files.

Both the computer and hub require a static IP or DHCP reservation on the router.

On Hubitat:

Create a new device using the EventGhost Bridge driver. In Preferences, set the host/IPV4 to the computer's IP, set the port (8081 recommended, must match the port set on the computer), and enter the computer's MAC address. MAC addresses can be ##:##:##:##:##:##, ##-##-##-##-##-## or ##.##.##.##.##.##, case-insensitive. Different event names trigger different computer script responses, and you can rename these if you wish. Expected heartbeat interval is the repeat which the computer needs to use to ping the hub to notify/respond/confirm it is awake. Click Save Preferences from a desktop browser, then reload and confirm. Note the Device ID, which is in the URL of the device page when opened in its own tab, or is the first text in log entries from the device.

Go to the Maker API app page and authorize the new device. Note the MakerAPI app ID and access token, which can be found in the sample URLs at the bottom of the page.

Install the Computer Monitor Parent app, then add a child instance pointed at the new device. You can choose timing intervals (independent of the ping heartbeat) after missing a ping to first check (by requesting a ping), then to time out and turn the device off. You can have the hub use the network ping to see if the device is present on the network, but not responding to Hubitat requests, and set a device to receive notifications if it seems like EventGhost or the response script has crashed.

On your computer: 

Development at this time includes Windows 11 (also older versions) with EventGhost and Linux via script.
