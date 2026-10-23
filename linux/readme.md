Files to implement in Linux.  Developed for Mint / XCFE. 

Both the computer and hub require a static IP or DHCP reservation on the router.

On Hubitat:

1. Create a new device using the EventGhost Bridge driver.
In Preferences, set the host/IP to the computer's IP, set the port (8081 recommended, must match the port set on the computer), and enter the computer's MAC address. MAC addresses can be ##:##:##:##:##:##, ##-##-##-##-##-## or ##.##.##.##.##.##, case-insensitive.  Different event names will trigger different computer script responses, and you can rename these if you wish. Expected heartbeat interval is the repeat which the computer will use to ping the hub to confirm it is awake. Click Save Preferences from a desktop browser, then reload and confirm. Note the Device ID, which is in the URL of the device page when opened in its own tab, or is the first text in log entries from the device.

2. Go to the Maker API app page and authorize the new device. Note the app ID and access token, which can be found in the sample URLs at the bottom of the page.

Install the Computer Monitor Parent app, then add a child instance pointed at the new device. You can choose timing intervals (independent of the ping heartbeat) after missing a ping to first check (by requesting a ping), then to time out and turn the device off.  You can have the hub use the network ping to see if the device is present on the network, but not responding to Hubitat requests, and set a device to receive notifications if it seems like EventGhost or the response script has crashed.

Install packages on the computer:

1. Install python3-dbus and python3-gi
   
bash
sudo apt install python3-dbus python3-gi libnotify-bin curl ethtool

2. Copy eg-bridge-agent.py to /home/<user>/eg-bridge-agent.py.
Edit the constants at the top: HUB_IP, MAKER_API_APP_ID, DEVICE_ID, ACCESS_TOKEN.
Change LISTEN_PORT. The file defaults to 80, which a non-root service can’t bind. Use 8081 (or whatever you set on the device preferences). The two must match.
Test it manually with python3 ~/eg-bridge-agent.py. The device’s Logs tab on Hubitat should show heartbeat() received.

3. Configure linux firewall

bash
sudo ufw status
sudo ufw allow 8081/tcp    # only if ufw is active

Without this, packets can arrive at the NIC (visible in tcpdump) but never reach the script. Verify with the device’s Trigger All Events command and watch the script’s console to make sure they land.

4. Install the user service

bash
mkdir -p ~/.config/systemd/user
cp eg-bridge-agent.service ~/.config/systemd/user/

Edit the copy and replace REPLACE_WITH_USERNAME in ExecStart with the real username (absolute path, no ~). Confirm WantedBy=default.target. The older multi-user.target doesn’t exist for user services, so the agent wouldn’t start at boot. Then:

bash
systemctl --user daemon-reload
systemctl --user enable --now eg-bridge-agent
loginctl enable-linger <user>
systemctl --user status eg-bridge-agent
journalctl --user -u eg-bridge-agent -f

--user is needed on both the systemctl and journalctl commands.

5. Polkit rule (needed if more than one account uses the machine)
Edit 10-eg-bridge-suspend.rules and change <user> to the desired username. Then:

bash
sudo cp 10-eg-bridge-suspend.rules /etc/polkit-1/rules.d/
sudo systemctl restart polkit

This lets the agent suspend the machine even when another account has the active session. Without it, polkit can queue a password prompt that surfaces at the next login. I’d test it once by logging in as the other account and triggering a sleep from Hubitat. On a single-account machine you can likely skip it.

6. Cancel-sleep keyboard shortcut (per account)
In the account’s keyboard settings, add a custom shortcut launched from your preferred hotkey combo (I use Ctrl + Win Key) that runs:

bash
curl "http://localhost:8081/?Hubitat.Cancel.Sleep.Request"

When Hubitat transmits a sleep request (triggered by toggling the bridge device off), the scripts you just installed will suspend the machine after a specified interval unless the Hubitat.Cancel.Sleep.Request is received.  If no sleep request is pending, then this is a harmless no-op.

7. Enable Wake-on-LAN

Check support with sudo ethtool <iface>, and look for g under “Supports Wake-on”.
Enable it with sudo ethtool -s <iface> wol g.
Make it persistent with nmcli connection modify "<wired connection name>" 802-3-ethernet.wake-on-lan magic.
Enable Wake on LAN in the BIOS/UEFI.

Final checks:

Once both devices are set up, a heartbeat should appears on Hubitat about every 3 minutes when the computer is awake.
The Ping button on the bridge device page in Hubitat gets a heartbeat reply.
off() from the hubitat device shows the toast on the computer and then suspends if not cancelled.  off() from the hubitat device followed by on of the cancellation shortcut cancels the suspend.
After suspending the computer, then resuming, a heartbeat arrives at Hubitat promptly.
on() from the hubitat device wakes the computer via WoL.
After a reboot, the service starts on its own with nobody logged in, and heartbeats start arriving at Hubitat.

The access token is a secret, so keep it out of any shared copy of the docs.
Toasts appear only in the account that owns the service. A different account that’s active won’t see them, though the machine still suspends.
