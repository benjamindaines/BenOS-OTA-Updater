# BenOS OTA Updater

Based on Custota, but not really intended for general use.  Code posted for GPL compliance and reference. 

## Changes
- Adds a UI for displaying messages along with updates
- Uses bespoke ROM prop value timestamp along with the Q25.json file and Q25.md files
- Adds remote, private key signed & verified on device, rule provisioning for pre-reboot actions.  This private key is separate from the platform key, assuring I am the only one able to push these rules. 
- Backs up user app data into NeoBackup-compatible backups before uninstalling packages that will conflict with added system packages.  Data can be restored into the system apps after reboot.
- UI Modifications, hides some things under the debug options
- Runs as a privilaged platform app on BenOS, no modules  
  - Elevated permissions to allow unattended package removal, but has no access to user data.  Backups are done via a separate privilaged binary that sends data via a socket.  The updater app and this helper binary live in their own, separate SELinux domains to prevent potential misuse for data exfiltration.  Anything that would be able to take advantage would already have root access and effectively own your device, regardless. 
