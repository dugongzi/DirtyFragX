# Changelog

## Unreleased

- Watching [chisewaguri/DFRoot](https://github.com/chisewaguri/DFRoot/tree/upstream-standalone): it drops
  the separate bootstrap binary and the staged copy of ksud, and has the kernel module exec the selected
  manager's own `libksud.so`, passing the package name and both switches as insmod parameters. That is
  worth taking once it is rebased onto upstream 4.5 and lands upstream - on its own the branch re-adds the
  `rmmod oplus_*` chain behind issue #21, and it only writes the `dfm0`/`dfm1` markers our progress bar
  does not read.

## 2.0.0

The first release under the DirtyFragX name. The exploit engine is unchanged from 1.16 - everything
below is the app around it.

### App - new interface
- The app is split into two pages behind a pager: `Home`, which carries the run flow, and
  `Settings`, which collects the options that used to be spread across dialogs.
- The exploit now runs on its own screen (`RunActivity`) instead of inside a modal dialog, so the
  progress, the log and the device information stay readable while it runs.
- `Home` is a draggable card stack: the SU manager prerequisite and the current status sit above
  the run button, and the status pill follows `Waiting` / `Running` / `Rooted` / `Verification` /
  `Verified` / `Failure` / `Reboot required`.
- The log can be copied in one tap, and `Advanced logs` switches the run panel from the summary to
  the full output.

### App - settings
- Light and dark themes, plus an accent tone that recolours buttons, icons and highlights.
- Language switch between English and Simplified Chinese.
- `Expert mode` hides autorun and auto soft reboot behind an explicit toggle.
- KernelSU module enable/disable, soft reboot, and a copyable device info block (device, system,
  kernel, arch, fingerprint).
- `Tutorial` opens the walkthrough page; the about section links to this project and to the
  original DirtyFrag repository.

### App - updates and announcements
- In-app update check: it reads the latest release, downloads the APK and hands it to the system
  installer, and reports a failed download, a non-APK payload or a missing "install unknown apps"
  permission separately.
- Announcement dialog for one-off notices, with `Got it` and a shortcut to the tutorial.

### App - identity
- Renamed from DFRoot to DirtyFragX, with a new adaptive launcher icon (black foreground on white,
  plus a monochrome layer for Android 13+ themed icons) and a console banner that reads
  `DIRTYFRAGX`.
- GPL-3.0 licence text added to the repository.

### Build and release
- The `Build` workflow now belongs to this project: eight `LKMs / <kmi>` jobs build the kernel
  modules, `build-dfroot` assembles the APK, and a `v*` tag publishes `DirtyFragX-<tag>.apk` with
  notes taken from this file.
- Release builds are signed with `app/keystore.jks`, restored from the `KEYSTORE` secret in CI, so
  every APK from this fork shares one signing identity. Pull-request builds fall back to the debug
  key instead of failing.
- The APK is always written as `dirtyfragx.apk`, and the GitHub release is mirrored to Gitee.

## 1.16

Based on upstream DFRoot 4.5 (upstream 4.2 - 4.5 merged into this fork).

### Engine - vendor hardening is patched in place instead of removed (issue #21)
- The kernel module no longer runs `rmmod oplus_secure_harden`, `rmmod oplus_security_keventupload` and
  `rmmod oplus_security_guard`. Removing those modules on an Oppo/OnePlus kernel tripped the vendor
  security anti-tamper path and the device rebooted to a black screen on every run - reported on a OnePlus
  Ace6 (VT0X5AOWUU7U/ocloud-cn, N123, ColorOS 16.0.7.101).
- A new `patch_fn_ret_zero()` writes `mov w0, #0; ret` over the first two instructions of the handlers
  that were causing the trouble, so the modules stay loaded and their checks simply return allowed. It is
  applied to `oplus_exe_block_ret_handler` and `oplus_root_check_post_handler`, found through
  `module_kallsyms_lookup_name`, and to Samsung's `task_defex_enforce` and `task_defex_user_exec`, which
  in 1.15 were kprobes that rewrote `x0` on every hit. The patcher skips a `BTI`/`PACIASP`/`PACIBSP`
  preamble and emits `retaa`/`retab` only when the SoC really has pointer authentication.
- `call_usermodehelper_exec` is called with `UMH_WAIT_EXEC` instead of `UMH_WAIT_PROC`, so the module no
  longer waits for the helper's exit status. That wait is the other half of the MTK reboot, and the status
  it reported is what a Samsung saw as `returned 256` in 1.15 (issue #5). The helper command is now simply
  `touch /dev/dfm0; exec /data/user_de/0/df.root/bootstrap`, so the `exit 0` workaround from 1.15 is gone:
  there is no longer a failing command at the end of the chain to mask.
- Housekeeping around the module: it now fails its own init with `-ENODEV` to get itself unloaded, and
  bootstrap runs `rmmod dfroot` once late-load has finished and before a soft-reboot, rather than only at
  the very end where a reboot skipped it.
- The `.ko` keep their `.note.gnu.build-id` section. Upstream stopped stripping it, which is what kept a
  Galaxy A34 (SM-A346M, kernel 6.6.92) from loading the module at all.

### Engine - the payload gets a place to live on more devices
- The loader used to put the shellcode straight after the end of the executable `PT_LOAD` segment and give
  up if the remaining slack in that page was too small, with a bare `[-] Failed to find a suitable code
  cave`. It now scans the executable segment for a run of zero bytes - the alignment padding between
  functions - that is big enough, prints the offset it chose, and reports the largest run it found when
  there is none, so a log says something useful instead of nothing.
- The hook site preamble is now recognised properly: `PACIASP`, `PACIBSP`, `BTI`, `BTI c`, `BTI j` and
  `BTI jc`, up to two words deep, where before only `PACIASP` and one form of `BTI` were handled once.
- With the shellcode no longer always at the end of the segment, the branch back to the hook and the saved
  first instruction were being written into the wrong buffer, which produced a patch that crashed instead
  of hopping. Both are now written into the padded buffer that is actually placed in the cave.

### App
- The run banner in the advanced log opens with `app version:`, so a pasted log says which build produced
  it; it reads the same `BuildConfig.VERSION_NAME` the header used to carry.
- `run()` is `synchronized` and returns early when `/dev/df` already exists, so the boot autorun racing a
  manual tap no longer re-hooks over the first run and corrupts it.
- The manager check became a `hasKsud()` helper, which tests `nativeLibraryDir` for null first.
- The interface keeps its own scale. Every measurement in it belongs to one screen width in dp - the Run
  pill is 48% of its parent minus a fixed 66dp, the toolbar and the 54dp circles are fixed dp holding sp
  text - so a single One UI screen zoom step throws the pill allowance off and clips the title, and default
  zoom breaks it outright. `MainActivity` now wraps its base context through `ScaleFix`, which derives a
  density from the real pixel width so the app always lays out at 384dp, the width of a Galaxy S25 at
  450dpi, and pins `fontScale` to 1.0 because the Font size slider breaks those same containers on its own
  account. The correction is capped at 30% either side of what the device reports, and it returns the base
  context untouched at the calibration width, so a device already there pays nothing. Verified on a Galaxy
  S25 (SM-S931B): identical at every screen zoom and font size step. The price is that the system's own
  Screen zoom and Font size no longer apply inside the app.
- Wide screens are the known gap in that lock, not a solved case. The correction is capped at 30% either
  side of the density the device reports, and a foldable opened flat, or a tablet, needs more than that to
  reach 384dp from its pixel width, so there the canvas settles wider than the layout expects instead of
  exactly on it. Phone widths are what was measured and verified; a foldable or tablet runs, but the pill
  allowance and the toolbar alignment are not guaranteed to hold there.
- An **Odin** pill sits left of the menu button in the toolbar, cut from the same cloth as that button - the
  same 44dp height and the same #1C1C1C capsule - and its dot says whether this firmware will let the device
  into Odin. Galaxy firmwares from around April 2026 answer download mode with a D2 error while the device
  has a lock-screen credential, because `DmcService` in `system_server` writes `[lock, maintenance,
  at_command]` into a vaultkeeper trustlet in the TEE and the bootloader refuses Odin on `[1, 0, 0]`. That
  vault is behind a signature-or-privileged permission, and DirtyFrag is deliberately not a root client - it
  is the tool that gets you root, so it has to work on a device that has none - which is why reading the
  vault, as an earlier attempt at this feature did, could only ever answer "unsupported" on a real Galaxy.
  Instead it reads the two inputs the vault is built from, both public API: the lock-screen credential
  through `KeyguardManager`, and Samsung's maintenance mode through the Settings provider. Green means
  download mode is not gated, red means it is, and grey means this device does not gate download mode at all
  - which is also what a non-Samsung device now gets instead of a pill that vanished, so the control is
  always there to explain itself. Tapping it opens a submenu of the three facts behind the dot - Lockscreen
  Yes/No, Maintenance Mode On/Off, Device Supported/Unsupported - in the same card as the overflow menu,
  which beats a coloured dot you have to argue with by cable; the same line still goes to logcat.
  `at_command` is the one input an app cannot read; it is assumed cleared, which is what it is after every
  boot unless a service tool has just authorised the device, and that can only make the dot too pessimistic,
  never too optimistic.
- No more toasts. Nothing in this app should be announced over the bottom of the screen for a second and
  then vanish: resetting the layout, flipping the KSU modules switch, tapping a manager that cannot be
  launched. The modules switch already says what it did in its own subtitle, the reset is visible in the
  stack itself, and the two that looked like errors cannot strand the user - the manager launcher is only lit
  when there is something to launch, and a manager with no launchable activity goes to logcat.
- The version number is out of the header and the update announcement has taken its place. The header is the
  app's name, the **Odin** control and the menu button; when the release check comes back with something newer,
  a lime capsule wipes in from the left edge over the word **DirtyFrag** and reads **Update**, one tap from the
  release page. What it replaces is a small pill over the version digits, drawn inside the title's own text
  line: 10.8sp next to a 24sp word is not readable at arm's length, and it reserved its width from the first
  frame whether or not an update was flagged, which with the wider Odin pill beside it left the title ellipsized
  - "DirtyFrag…", version pill and all. A span cannot be the 44dp module everything else in the header is, so
  the announcement is a View now, with 24dp of air on each side of its word: 158.9dp measured on a Galaxy S25
  (SM-S931B), left edge on the headline's own, 54dp still clear of the Odin pill. An up to date header pays
  nothing for it - the pill does not exist until the check answers, and the title no longer shares its line with
  anything it has to make room for. The version is not lost, only relocated: `version <x>` to logcat on every
  start, `app version: <x>` in the run log.

### Managers
- zandatsu07's KernelSU-Next is no longer listed as a Samsung manager, following upstream: it only
  worked on S23 and below with kernel 5.15, so it is dropped from the list until it works on every
  Samsung device. diabl0w's KernelSU stays the Samsung manager and the non-Samsung list is unchanged.

### Fixed - unsafe getprop fallback
Upstream added a fallback for kernels that leave the Android version out of `uname -r`, reading
`ro.vendor.build.version.release`. It never checked the `popen()` result and rebuilt the version string
with `sprintf` into a 15 byte stack buffer from a 5 byte read, so a long or non-numeric property
overflowed it. The property is now read into a zero-initialised buffer and only a leading integer is
accepted, which is all the caller wants. The same hardening shipped in
[k10halifa/Dslice](https://github.com/k10halifa/Dslice).

### Built
- The eight embedded kernel modules are rebuilt by CI from this source and committed, so the APK carries
  exactly this engine.
- The bundled Inter variable font is subset down to the glyphs the app can render: 2,937 glyphs and 2,852
  codepoints become 829 and 427, 879,708 bytes become 239,308, and that takes **332,804 bytes out of the
  APK** - 2.26 MB down to 1.93 MB, about a seventh of the download. It is the same font: Inter carries no
  hinting tables at all, so a subset cannot change how a surviving glyph rasterises, and that was checked
  rather than assumed - both fonts were instantiated at the two weights `res/font/inter.xml` declares and
  every contour coordinate and advance width compared over 86 characters, which is every character the app
  can put on screen, at zero differences. Both variation axes and all nine named instances are kept, and the
  only OpenType features dropped are the ones that are off unless something asks for them (stylistic sets,
  `cv01`-`cv14`, `ss01`-`ss08`, `dlig`, `frac`, `aalt`, ...). What is left covers Basic Latin, Latin-1,
  combining marks, general punctuation, arrows, geometric shapes and dingbats. Anything outside that is not
  lost: Android falls back per character, so a Cyrillic module description renders in the system font the
  way emoji already did. `tools/subset_font.py` regenerates it, with `--latin-only` and `--wide` presets if
  the coverage needs to move either way.
- `android:debuggable="true"` is gone from the manifest, where upstream has carried it since its first
  commit. It is not free: a debuggable APK can be `run-as` and ptraced by anything that has adb, which for
  this app means readable preferences - the chosen SU manager, the module and autorun switches - and an
  injectable process sitting next to an exploit. Nothing in the app needs it and no code path reaches for a
  hidden API, so removing it changes no behaviour. Debug builds are still debuggable, because the debug
  build type does that on its own.

## 1.15

### Movable card UI
- The four cards are a stack you can rearrange. Hold one and it snaps under your finger, drop it where
  you want it and the rest settle around it. The order is kept, so it survives a reboot and a re-run
  of the exploit.
- Every card except SU Manager can be hidden with the minus chip on its edge - the SU Manager card
  refuses to be hidden so the stack can never be emptied by mistake - and **Menu -> Reset layout**
  brings the whole stack back.
- The advanced log belongs to the "Show advanced log" card: it hangs off that card's bottom edge,
  travels with it, and takes its room with it the moment the card is lifted or hidden.

### Engine - the run no longer dies at the verify stage (issue #5)
- The kernel module's usermodehelper command is a `sh -c` chain that ended in
  `rmmod oplus_security_guard`. Those three `rmmod` calls are Oppo/OnePlus only, so on every other
  kernel the last command failed, `sh -c` returned its exit code and `call_usermodehelper_exec`
  reported `returned 256` - the bootstrap never started and the run stopped at the verify stage. The
  chain now ends in `exit 0`. Found and verified by @BrokenByteOfCode on a Samsung A07 4G (SM-A075F,
  android16-6.12).
- The eight embedded kernel modules were rebuilt from this source for this release, so the fix reaches
  devices through the APK.

## 1.11

Based on upstream DFRoot 4.1 (upstream 3.0 - 4.1 merged into this fork).

### Engine - upstream DFRoot 4.0 merged
- OPPO/OnePlus bypasses added to the kernel module
- the OPPO security modules (`oplus_secure_harden`, `oplus_security_keventupload`,
  `oplus_security_guard`) are `rmmod`-ed before the bootstrap handoff
- unneeded kprobes removed from the module
- upstream v4.0 release work: compile cleanup, minified release build, smaller APK
### Engine - upstream DFRoot 4.1 merged
- soft reboot now works with every KernelSU derivative. `dfroot.ko` keeps its kprobes in static
  globals and gains a real `module_exit`, so the bootstrap can `rmmod dfroot` once late-load is
  done instead of the module self-unloading by failing its own init; the ksud `soft-reboot` is
  execed in the `u:r:ksu:s0` context only after the late-load process has exited
- the manager picker lists only apps that actually ship `libksud.so` - this fork already did that,
  so the behaviour is unchanged
- the eight embedded kernel modules were rebuilt from the 4.1 `lkm/dfroot.c` (one per KMI, built by
  this repo's CI), so the shipped modules match the marker table the app reads

### DirtyFrag
- Every run log starts with a device banner - manufacturer, model, Android version, security patch
  level and kernel version - so a pasted log can be triaged without asking follow-up questions
- The log header is a plain "SYSTEM" line instead of a firmware build token
- The launcher icon is a vector (adaptive foreground, background and monochrome) instead of five
  PNG densities: the APK is 2.24 MB instead of 2.91 MB
- The progress bar is strictly linear: the run bar reaches 100% when the module is in, and only
  then does the verification bar start moving
- Build moved to AGP 9.4.0 with compile/target SDK 37
- BakaSU is listed as a manager for non-Samsung devices
- zandatsu07's KernelSU-Next is listed as a manager for Samsung devices

### Samsung devices: which manager to install
Samsung firmware enforces its own KDP/DEFEX credential handling, so a stock KernelSU build cannot
take root there. Use one of the Samsung builds of KernelSU or KernelSU-Next instead:

- [diabl0w's KernelSU](https://github.com/diabl0w/KernelSU/releases/tag/samsung-v1.0)
- [zandatsu07's KernelSU-Next](https://github.com/zandatsu07/KernelSU-Next/releases/latest)

Install it, pick it in DirtyFrag, and turn on Auto reboot if you want the soft reboot.

### Not in this release: the BakaSU manager
During this cycle the Samsung KDP/DEFEX handling was ported into BakaSU (ex-ReSukiSU) so that
Samsung devices could use it as the manager. The port does not work yet: on the test device
(SM-S931B) the device breaks right after the grant, and a control build with the port compiled out
fails the same way, so the fault is not the port itself. It is not part of 1.11 and will be merged
later once it is fixed. Until then Samsung devices must use one of the Samsung builds linked
above.

## 1.10

Based on upstream DFRoot 3.3 (upstream 3.0 - 3.3 merged into this fork).

### Engine
- The exploit is no longer a JNI library: it runs as a staged executable and hands off to a
  bootstrap binary. The bootstrap reads the app's device-protected settings, adopts the zygote
  environment, sets partitions read-only, optionally disables all modules, and only then starts the
  SU daemon
- Runtime kallsyms resolution of `kern_path`, `invalidate_inode_pages2` and `path_put`, which fixes
  namespace violations on some Android 13 kernels
- `__nocfi` annotations on the kernel module - the upstream issue #68 class of crashes on kernels
  with control-flow integrity
- libc++ restore-ordering fix
- `ksud` is staged from the selected SU manager instead of being bundled with the app. The APK is
  2.9 MB instead of 6.5 MB

### DirtyFrag
- Pick your SU manager inside the app; Run stays disabled until one is selected
- KernelSU Modules and soft reboot now work without root
- Autorun keeps its Expert-mode gate and its failed-run interlock
- SU-manager launcher button: a circle while a run is in flight, opening into a "Manager" pill once
  root is verified
- Save the log to Downloads from the circle beside the run pill, available after any finished run
- Neutral grey press highlights throughout, and the Material purple tints are gone - including the
  purple flash for a moment on cold start
- The eight prebuilt kernel modules are committed so that a clone of this repository can build

## 1.08
- KernelSU Modules toggle
- Clearer status text and a refresh when returning to the app

## Earlier
See the release notes on the releases page.
