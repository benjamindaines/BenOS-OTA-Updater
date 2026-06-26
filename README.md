# BenOS OTA Updater

Based on Custota, but not really intended for general use.  Code posted for GPL compliance and reference. 

## Changes
- Adds a UI for displaying messages along with updates
- Uses bespoke ROM prop value timestamp along with the Q25.json file and Q25.md files
- Adds remote, private key signed & verified on device, rule provisioning for pre-reboot actions.  This private key is separate from the platform key, assuring I am the only one able to push these rules. 
- Backs up user app data into NeoBackup-compatible backups before uninstalling packages that will conflict with added system packages.  Data can be restored into the system apps after reboot.
- UI Modifications, hides some things under the debug options
- Runs a privilaged platform app on BenOS, no modules  
