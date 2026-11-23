# Multi-GSI

Multi-GSI lets you keep several GSIs installed at the same time and switch between
them. Each GSI is installed in its own DSU slot, with its own system and its own
userdata, so installing a new one no longer means discarding the one you have.

> [!WARNING]
> Every installed GSI takes storage for its system image and its userdata. Several
> GSIs can fill the device quickly. Remove the ones you no longer use.

## Requirements

- Root **or** Shizuku.
- Android 11 or newer.

The built-in installer is not needed. Over plain adb (no root, no Shizuku) DSU Next only
prepares a command and Multi-GSI stays off.

| | Root | Shizuku |
| --- | --- | --- |
| Install into a slot | built-in installer, or the DSU app | the DSU app |
| Switch, remove | yes | yes |
| List of GSIs | read from gsid | kept by DSU Next, see [Without root](#without-root-shizuku) |

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

## Without root (Shizuku)

Shizuku runs DSU Next's service as `shell`. That is enough for everything except one call:

- **Installing** goes through the DSU app of your ROM (`com.android.dynsystem`). DSU Next
  passes the slot in the `KEY_DSU_SLOT` extra, which the app hands to its installation
  service. The ROM's DSU app has to support it. If it ignores the extra, the GSI is installed
  to the default slot `dsu` and can replace a GSI that is already there. DSU Next notices this
  after the installation and tells you.
- **Switching and removing** are gsid calls (`enableGsi`, `removeGsi`) that gsid accepts from
  `shell`.
- **Listing** is the exception: gsid lists slots for root and system only. Under Shizuku DSU
  Next shows the GSIs it installed itself, plus the slot gsid has as active. A GSI that another
  tool removed stays in the list until you remove it here, and a GSI that another tool installed
  only shows up while it is the active slot.
- **Reading the install state** (is a DSU running, is a GSI installed) is asked to gsid instead
  of the DynamicSystem service, which needs a permission that shell does not have.
- Without the log permission DSU Next cannot see the installation finish. The GSI is then
  registered the next time you open the Multi-GSI list.

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
| Multi-GSI needs root or Shizuku and Android 11 or newer | The device or the operation mode is not supported. | Grant root or Shizuku to DSU Next, then reopen the screen. |
| Couldn't reach the DSU service | Root or Shizuku was not granted, or gsid did not answer. | Check the grant and try again. SELinux on some ROMs does not let `shell` talk to gsid, use root there. |
| The DSU app ignored the slot name | The ROM's DSU app does not read `KEY_DSU_SLOT`. | Use root with the built-in installer on this ROM. |
| A DSU is running right now | You are booted into a DSU. | Reboot into your normal system. |
| Couldn't switch to that GSI / Couldn't remove that GSI | gsid refused the request. | Read the logcat lines tagged `GsiSlotController`, they say which gsid call failed. |
| Multi-GSI is off | The setting is off, so a new installation replaces the installed DSU. | Turn it on in **Settings > Installation**. |
| Couldn't restart the device | The reboot command was refused. | Restart the device yourself, the chosen GSI is already selected. |
