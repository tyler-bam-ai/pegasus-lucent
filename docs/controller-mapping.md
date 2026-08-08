# Lucent controller mapping

Per-system mapping from the AYN Thor's physical controls to each console's
controls. The runtime tables live in
[`LibretroJoypadLayout`](../unified-android/src/com/thorium/lucent/input/LibretroJoypadLayout.java)
(physical geometry to libretro RetroPad ID) and
[`SystemControlLayouts`](../unified-android/src/com/thorium/lucent/input/SystemControlLayouts.java)
(the same mapping expressed with each console's own button names, used by the
remap editor). Console control names below are those tables; RetroPad IDs are
taken from the pinned core's own `retro_input_descriptor` set, not from
convention.

## The Thor's physical controls

| Physical control | evdev | Android | Lucent canonical |
| --- | --- | --- | --- |
| Bottom face button (labelled **A**) | `BTN_SOUTH` 304 | `KEYCODE_BUTTON_A` 96 | `SOUTH` |
| Right face button (**B**) | `BTN_EAST` 305 | `KEYCODE_BUTTON_B` 97 | `EAST` |
| Left face button (**X**) | `BTN_NORTH` 307 | `KEYCODE_BUTTON_X` 99 | `WEST` |
| Top face button (**Y**) | `BTN_WEST` 308 | `KEYCODE_BUTTON_Y` 100 | `NORTH` |
| L1 / R1 | `BTN_TL` 310 / `BTN_TR` 311 | `BUTTON_L1` 102 / `BUTTON_R1` 103 | `L1` / `R1` |
| L2 / R2 | `BTN_TL2` 312 / `BTN_TR2` 313 | `BUTTON_L2` 104 / `BUTTON_R2` 105 | `L2` / `R2` |
| D-pad | `ABS_HAT0X` / `ABS_HAT0Y` | `AXIS_HAT_X` / `AXIS_HAT_Y` | `DPAD_*` |
| Left stick | `ABS_X` / `ABS_Y` | `AXIS_X` / `AXIS_Y` | `LEFT_X_*` / `LEFT_Y_*` |
| Left stick click | `BTN_THUMBL` 317 | `BUTTON_THUMBL` 106 | `L3` |
| Right stick | `ABS_Z` / `ABS_RZ` | `AXIS_Z` / `AXIS_RZ` | `RIGHT_X_*` / `RIGHT_Y_*` |
| Right stick click | `BTN_THUMBR` 318 | `BUTTON_THUMBR` 107 | `R3` |
| Start | `BTN_START` 315 | `BUTTON_START` 108 | `START` |
| Select / Stop | `BTN_SELECT` 314 | `BUTTON_SELECT` 109 | `SELECT` on tap; Lucent Stop on hold |

The Thor is an Xbox-style pad: bottom=A, right=B, left=X, top=Y. It reports its
D-pad as a hat, **not** as `BTN_DPAD_*` keys, so the D-pad and the left stick
both arrive as axes inside the same `MotionEvent`.

Select is dual-purpose. A tap is replayed into the game as a normal Select
press; a hold is Lucent's Stop gesture and never reaches the core.

## Rules that apply to every system

**The left stick is a second D-pad on every console that never had a stick.**
NES, SNES, Game Boy/Color/Advance, Master System, Game Gear, Genesis, SG-1000,
PC Engine, Neo Geo, arcade, every Atari system, C64, MSX, ZX Spectrum,
WonderSwan, Neo Geo Pocket, Virtual Boy, DS, PlayStation, DOS and ScummVM all
accept the stick and the D-pad at once. Neither cancels the other.

**Consoles whose core reads the left stick as its own analog control keep it off
the D-pad.** N64, GameCube, Wii, Dreamcast/NAOMI/Atomiswave and PSP games read
the stick and the D-pad as different inputs, so aliasing them would make stick
movement press the D-pad. `LibretroJoypadLayout.hasAnalogStick` is the list.

**The right stick is never a digital button.** It is sent only as
`RETRO_DEVICE_ANALOG` index 1, which is what the N64 C-buttons, the GameCube
C-stick and Wii IR pointing read.

**Several physical controls may drive one console control.** The hat, the left
stick and (on other pads) `BTN_DPAD_*` keys all mean "the D-pad".
[`JoypadPressLedger`](../unified-android/src/com/thorium/lucent/input/JoypadPressLedger.java)
records which sources hold each RetroPad ID and ORs them, so a direction stays
pressed while any source holds it and is released once, when the last lets go.
Without it the last source written wins, and one centred stick clears a
physically held hat direction.

## Nintendo handhelds and 8/16-bit consoles

`nes` `famicom` `snes` `superfamicom` `gb` `gbc` `gba` `virtualboy` `nds` `ds`

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 8 | **A** |
| Right (B) | 0 | **B** |
| Left (X) | 9 | X (SNES/GBA/DS) |
| Top (Y) | 1 | Y (SNES/GBA/DS) |
| L1 / R1 | 10 / 11 | L / R (SNES/GBA/DS); Virtual Boy L / R |
| L2 / R2 | 12 / 13 | unused |
| D-pad, left stick | 4-7 | D-pad |
| Right stick, L3 | — | unused |
| R3 | 15 | DS lid (`nds` only) |
| Start | 3 | Start |
| Select (tap) | 2 | Select |

*Why bottom = A:* these consoles put their confirm button on the **right** of
the face cluster, but the Thor's bottom button is the one physically labelled A
and is the one a player reaches for to confirm. Lucent matches the printed
labels rather than the console's geometry, so SNES B lands on the Thor's B
(right) button, not its bottom one. This is `isNintendoFace` in
`LibretroJoypadLayout`; every non-Nintendo system keeps the generic RetroPad
convention where the south position is B.

## Sega

`sg1000` `mastersystem` `gamegear` `genesis` `megadrive` `segacd` `megacd`
`sega32x`

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 0 | **B** |
| Right (B) | 8 | **C** |
| Left (X) | 1 | **A** |
| Top (Y) | 9 | X |
| L1 / R1 | 10 / 11 | Y / Z |
| D-pad, left stick | 4-7 | D-pad |
| Start | 3 | Start |
| Select (tap) | 2 | Mode |

*Why bottom = B:* a Genesis pad's three-button row is A-B-C left to right, and
B is the middle/primary action. Placing B, C, A on bottom, right, left keeps the
six-button row in its original left-to-right order across the Thor's face
cluster and its shoulders.

## PC Engine, Neo Geo Pocket, WonderSwan, Atari, ColecoVision, Intellivision, Odyssey²

`pcengine` `turbografx16` `ngp` `ngpc` `neogeopocket` `neogeopocketcolor`
`wonderswan` `wonderswancolor` `atari2600` `atari5200` `atari7800`
`colecovision` `intellivision` `odyssey2`

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 0 | **B** (PC Engine I / NGP A / WonderSwan A) |
| Right (B) | 8 | **A** (PC Engine II / NGP B / WonderSwan B) |
| D-pad, left stick | 4-7 | D-pad |
| Start | 3 | Run / Start |
| Select (tap) | 2 | Select |

These keep the generic RetroPad convention: the core's primary button is
RetroPad B, which is the Thor's bottom button.

## Arcade and Neo Geo

`arcade` `mame` `neogeo` `neogeocd`

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 0 | Button 1 |
| Right (B) | 8 | Button 2 |
| Left (X) | 1 | Button 3 |
| Top (Y) | 9 | Button 4 |
| L1 / R1 | 10 / 11 | Button 5 / Button 6 |
| L2 / R2 | 12 / 13 | Button 7 / Button 8 |
| D-pad, left stick | 4-7 | Joystick |
| Start | 3 | Start 1 |
| Select (tap) | 2 | Coin 1 |

*Why this order:* a Neo Geo cabinet's A-B-C-D row runs left to right, and the
Thor's bottom-right-left-top order reproduces the neutral-thumb reach of that
row, with the second row on the shoulders.

## Home computers, DOS and ScummVM

`c64` `commodore64` `amstradcpc` `atarist` `atari800` `atari8bit` `msx` `zx`
`zxspectrum` `dos` `windows9x` `scummvm`

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 0 | Fire 1 |
| Right (B) | 8 | Fire 2 |
| D-pad, left stick | 4-7 | Joystick / cursor |
| Right stick | analog 1 | core-defined (mouse look where supported) |
| Start | 3 | Start |
| Select (tap) | 2 | Menu |

## PlayStation

`psx` `ps1` `playstation`

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 0 | **Cross** |
| Right (B) | 8 | **Circle** |
| Left (X) | 1 | Square |
| Top (Y) | 9 | Triangle |
| L1 / R1 | 10 / 11 | L1 / R1 |
| L2 / R2 | 12 / 13 | L2 / R2 |
| L3 / R3 | 14 / 15 | L3 / R3 |
| D-pad, left stick | 4-7 | D-pad |
| Start / Select (tap) | 3 / 2 | Start / Select |

*Why bottom = Cross:* Cross is the PlayStation confirm button and sits at the
south position on a DualShock, which is where the Thor's bottom button is.

The in-process host selects `RETRO_DEVICE_JOYPAD` on port 0, which is a digital
pad on this core, so the left stick doubles as the D-pad rather than feeding an
analog stick the core would ignore.

## Nintendo 64

`n64` `nintendo64` — mupen64plus-next with its shipped
`mupen64plus-next-alt-map=False` default.

| Thor | RetroPad ID | N64 control |
| --- | --- | --- |
| Bottom (A) | 0 | **A** |
| Right (B) | 1 | **B** |
| Left (X) | 8 | C-Right, only while R2 is held (core option C1) |
| Top (Y) | 9 | C-Up, only while R2 is held (core option C4) |
| L1 | 10 | L shoulder |
| R1 | 11 | R shoulder |
| L2 | 12 | **Z trigger** |
| R2 | 13 | C-buttons modifier ("C Buttons Mode") |
| D-pad | 4-7 | D-pad |
| **Left stick** | analog index 0 | **Control Stick** |
| **Right stick** | analog index 1 | **C-Up / C-Down / C-Left / C-Right** |
| Start | 3 | Start |
| Select | — | unmapped; the N64 pad has no Select |

*Why the right stick is the C-buttons:* mupen64plus-next reads
`RETRO_DEVICE_ANALOG` index 1 directly and converts it into the four C
directions (`emulate_game_controller_via_libretro.c`: X<0 sets `L_CBUTTON`, X>0
`R_CBUTTON`, Y<0 `U_CBUTTON`, Y>0 `D_CBUTTON`), which is exactly a modern
right-stick camera. The face buttons remain a digital fallback for the two C
directions the core exposes without holding R2.

*Why the left stick no longer presses the D-pad:* N64 games read the Control
Stick and the D-pad as separate controls. Aliasing them made every stick
movement also press a D-pad direction.

*Previously wrong:* the generic RetroPad table sent the Thor's B (right) button
to RetroPad ID 8, which this core reads as a C-button rather than N64 B, and
sent X to ID 1 (N64 B). Both are fixed above.

## GameCube

`gamecube` `gc` `nintendogamecube` — Dolphin `descGC`.

| Thor | RetroPad ID | GameCube control |
| --- | --- | --- |
| Bottom (A) | 8 | **A** |
| Right (B) | 0 | **B** |
| Left (X) | 1 | **Y** |
| Top (Y) | 9 | **X** |
| L1 | — | unmapped (RetroPad L is Dolphin's Triforce test switch) |
| R1 | 11 | **Z** |
| L2 / R2 | 12 / 13 | L trigger / R trigger |
| D-pad | 4-7 | D-pad |
| **Left stick** | analog index 0 | **Control Stick** |
| **Right stick** | analog index 1 | **C-stick** |
| L3 / R3 | 14 / 15 | unused by the core |
| Start | 3 | Start |
| Select | — | unmapped (RetroPad Select is the Triforce coin switch) |

*Why left = Y and top = X:* the GameCube's kidney-shaped face cluster puts Y to
the left of A and X above it, so Lucent keeps the console's own geometry here
rather than the handheld X/Y arrangement.

## Wii

`wii` `nintendowii` — Dolphin `descWiimote` / `descWiimoteNunchuk`. One table is
used for both device types, because the two descriptors agree on A, B, the
D-pad, Home, "shake Wiimote" and both analog sticks.

| Thor | RetroPad ID | Bare Wiimote | Wiimote + Nunchuk |
| --- | --- | --- | --- |
| Bottom (A) | 8 | **A** | **A** |
| Right (B) | 0 | **B** | **B** |
| Left (X) | 9 | **1** | **Nunchuk C** |
| Top (Y) | 1 | **2** | **Nunchuk Z** |
| L1 | 10 | unused | **−** |
| R1 | 11 | unused | **+** |
| L2 | 12 | unused | shake Nunchuk |
| R2 | 13 | shake Wiimote | shake Wiimote |
| D-pad | 4-7 | D-pad | D-pad |
| **Left stick** | analog index 0 | Wiimote tilt | **Nunchuk stick** |
| **Right stick** | analog index 1 | **IR pointing** | **IR pointing** |
| L3 | 14 | unused | unused |
| R3 | 15 | Home | Home |
| Start | 3 | **+** | **1** |
| Select (tap) | 2 | **−** | **2** |

### Wii IR pointing

Dolphin's libretro frontend binds the Wiimote IR pointer from the core option
`dolphin_ir_mode`, whose shipped default is `"1"` — *Right Stick controls
pointer (absolute)*. In that mode `Input.cpp` sets the IR control expressions to
`devAnalog:X1±` / `devAnalog:Y1±`, which is `RETRO_DEVICE_ANALOG` index
`RETRO_DEVICE_INDEX_ANALOG_RIGHT`. Lucent already sends the physical right stick
on that index, so aiming works with no further wiring. Mode `"0"` is the
relative variant and `"2"` switches IR to `RETRO_DEVICE_POINTER` (a mouse or
touchscreen), which is not what a handheld wants.

### Wii nunchuk

The Wii extension is **not** a core option. Dolphin exposes it through
`RETRO_ENVIRONMENT_SET_CONTROLLER_INFO` and selects it in
`retro_set_controller_port_device`, which calls
`wmExtension->SetSelectedAttachment(ExtensionNumber::NUNCHUK)` for the device
type `RETRO_DEVICE_WIIMOTE_NC`, defined as `(3 << 8) | RETRO_DEVICE_JOYPAD`
(`0x301`). Lucent declares that value as
`LibretroJoypadLayout.WIIMOTE_NUNCHUK`, and `portDeviceFor("wii")` returns it.

**Not yet in effect.** The in-process host
(`unified-android/native/lucent_libretro_host.c`) calls
`set_controller_port_device(0, RETRO_DEVICE_JOYPAD)` unconditionally and exposes
no JNI entry point to change it, so Wii currently runs as a bare Wiimote and
extension-only titles (Super Mario Galaxy 2) will not accept input. Completing
this needs one native change, owned by the native workstream:

1. add `nativeSetControllerPortDevice(long handle, int port, int device)` to
   `LibretroHost` and the host `.c`, forwarding to
   `retro_set_controller_port_device`;
2. call it right after `loadGame` with
   `LibretroJoypadLayout.portDeviceFor(systemId)`.

The button table above is already correct for that device type, so no mapping
change is needed when it lands.

## PSP

`psp` — PPSSPP, run through `PpssppGlesEngineSession`.

| Thor | RetroPad ID | PSP control |
| --- | --- | --- |
| Bottom (A) | 0 | **Cross** |
| Right (B) | 8 | **Circle** |
| Left (X) | 1 | Square |
| Top (Y) | 9 | Triangle |
| L1 / R1 | 10 / 11 | L / R |
| D-pad | 4-7 | D-pad |
| **Left stick** | analog index 0 | **Analog nub** |
| Start / Select (tap) | 3 / 2 | Start / Select |

## Dreamcast, NAOMI, Atomiswave

`dreamcast` `naomi` `atomiswave` — Flycast.

| Thor | RetroPad ID | Console control |
| --- | --- | --- |
| Bottom (A) | 0 | **A** |
| Right (B) | 8 | **B** |
| Left (X) | 1 | **Y** |
| Top (Y) | 9 | **X** |
| L1 / R1, L2 / R2 | 10 / 11, 12 / 13 | L / R triggers (analog on the core side) |
| D-pad | 4-7 | D-pad |
| **Left stick** | analog index 0 | **Analog stick** |
| Start | 3 | Start |

## Other in-process systems

These use the generic RetroPad table: bottom=RetroPad B (0), right=A (8),
left=Y (1), top=X (9), L1/R1=10/11, L2/R2=12/13, L3/R3=14/15, D-pad and left
stick=4-7, Start=3, Select=2.

`saturn` `ps2` `amiga` `amigacd32` `apple2` `jaguar` `3ds` `3do` `psvita`
`xbox` `xbox360` `windows`

## Systems that route to an external emulator

Lucent does not map controls for these; the external emulator owns its own
input. See [`external-emulator-routing.md`](external-emulator-routing.md).

| System | Route |
| --- | --- |
| `wiiu` | external Cemu |
| `switch` | external Eden |
| `3ds` | external Azahar or RetroArch (an internal core also exists) |
| `ps3` | no maintained Android emulator; cannot launch on-device |

## Remapping

Every mapping above is a default. The in-game Controls menu remaps any canonical
control per system or per game (`InputRouter` + `FileRemapStore`), and a remap
that moves a live source releases the RetroPad ID it used to hold, so a button
held across a remap never sticks down.
