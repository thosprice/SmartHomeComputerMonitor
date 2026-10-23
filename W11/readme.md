Sample W11 / Eventghost implementation.

You can load the xml file into EventGhost after installation.  The file can be saved anywhere and loaded from the downloads folder,
but you may want to create a folder for it.  

EventGhost configuration includes the following steps on the Windows machine.  
First, go to the MakerAPI page on Hubitat and find the APP_ID and ACCESS_TOKEN (in the sample URLs at the bottom of the page.
Then, find the DEVICE_ID for your computer bridge device (create one if it doesn't exist). It is the in the URL of the device
page if it is opened into its own tab; alternately, it is the first test on log entries from the device.
Use these three values to create the eg.globals.target_url variable in EventGhost Autostart.  Right-click on the action and
select Configure.

Right-click on the Webserver plugin and make sure it is using the same port set in the HE computer bridge device preferences.

EventGhost will receive events from the hub, sent to the computer's IP.  It is possible that a firewall or antivirus software
will block the incoming HTTP.  It's not possible to offer any further specific advice on configuring your system.
