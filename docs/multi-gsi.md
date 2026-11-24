# Multi-GSI

Multi-GSI lets you keep several GSIs installed at the same time and switch between
them. Each GSI is installed in its own DSU slot, with its own system and its own
userdata, so installing a new one no longer means discarding the one you have.

> [!WARNING]
> Every installed GSI takes storage for its system image and its userdata. Several
> GSIs can fill the device quickly. Remove the ones you no longer use.

## Requirements

- Root access.
- Android 11 or newer.
- The built-in installer. Turning on Multi-GSI turns it on as well.

Without root, or with Shizuku or adb, DSU Next installs a single GSI as before.
Choosing a slot is only possible through the built-in installer.

## Turn it on

1. Open **Settings > Installation**.
2. Switch **Multi-GSI** on and confirm.

Multi-GSI is off by default, so nothing changes for you until you switch it on.

## Install another GSI

Install as usual. While Multi-GSI is on:

- The home screen no longer stops at "A DSU installation is already present".
- The confirmation dialog has a **GSI name** field. It starts with the file name
  and is what you will see in the list. Leave it or type your own.
- The GSI goes to a new slot. The GSIs that are already installed are left alone.

A DSU cannot be installed while a DSU is running. Reboot into your normal system first.

## Switch between GSIs

Open **Tools > Multi-GSI**. The list shows every installed GSI, and the one marked
**Active** is the one gsid boots when DSU is enabled. Tap a GSI to:

| Action | What happens |
| --- | --- |
| **Switch to this GSI** | Selects it for the next DSU boot. **Switch & reboot** restarts the device right away, **Switch only** keeps the choice until your next restart. |
| **Rename** | Changes the name shown in the list. It does not touch the GSI. |
| **Remove** | Deletes that GSI and everything stored in it. The other GSIs are not touched. |

A switch is one-shot: the device boots the GSI once, and the reboot after that
returns to your normal system. To go from one GSI to another, reboot into your
normal system, open the list again and switch.

Switching and removing do not work while a DSU is running. The list says so.

## How it works

gsid, the service behind DSU, stores every installation in a named slot. DSU Next
installs each GSI in a slot of its own and talks to gsid directly to list, select
and remove slots. gsid does not keep names, so DSU Next remembers the name, the
source file and the install date of each GSI on its own. A slot that DSU Next did
not install still shows up, with the slot id as its name, and can be renamed.

Removing a GSI selects its slot first, because gsid removes the active slot. DSU
Next then restores the previously active slot, so removing one GSI does not change
which one the device boots.

## Troubleshooting

| Message | Cause | What to try |
| --- | --- | --- |
| Multi-GSI needs root and Android 11 or newer | The device or the operation mode is not supported. | Grant root to DSU Next, then reopen the screen. |
| Couldn't reach the DSU service | Root was not granted, or gsid did not answer the slot queries. | Check the root grant and try again. Some ROMs ship a gsid that does not expose slots. |
| A DSU is running right now | You are booted into a DSU. | Reboot into your normal system. |
| Couldn't switch to that GSI / Couldn't remove that GSI | gsid refused the request. | Read the logcat lines tagged `GsiSlotController`, they say which gsid call failed. |
| Multi-GSI is off | The setting is off, so a new installation replaces the installed DSU. | Turn it on in **Settings > Installation**. |
