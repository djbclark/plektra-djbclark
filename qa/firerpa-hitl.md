# Prompt: Plektra FIRERPA HITL QA harness

Fork-only (never in an upstream PR). This is the prompt. Incorporates `~/Downloads/plektra-qa.txt`
(2026-11-15) and replaces its assumption that FireRPA is already running.

You are an Android QA engineer working in this worktree. Build the harness in sections 3–7
only after FIRERPA is installed and configured on the user's own Android device over adb.
Do that device work yourself with `adb`. Do not stop at writing these steps down. Do not use
a stayturgid checkout, the Ansible role, a fleet converge, Termux, Magisk, or the FIRERPA APK.
The phone stays unrooted: `adb shell` is UID 2000.

API names below are the lamda 10.12 client (`Device`, `Selector`, `clear_text_field`), checked
against [djbclark/lamda](https://github.com/djbclark/lamda/commit/e91a88f5e75793ca22a1349855ab0096e7d06ab3).
Titan 2 Elite facts stay in `qa/titan2-elite-facts.md`.

## 1. Install and configure the user's device over adb

### 1.1 Choose the device

```bash
adb devices -l
```

One device in state `device`: that serial is `ADB_TARGET` (a USB serial or `host:port`).
Several: ask the user which serial is theirs, then use only that one. None: stop and ask
them to authorize USB debugging or connect wireless adb. Do not install on a serial they
did not accept when more than one is listed.

```bash
adb -s "$ADB_TARGET" get-state          # device
adb -s "$ADB_TARGET" shell id -u         # 2000
adb -s "$ADB_TARGET" shell getprop ro.product.cpu.abi
```

Pixel 7a and the Titan 2 Elite are `arm64-v8a`. Set `FIRERPA_ARCH` to the property you just
read. Refuse to push an arm64 tarball onto any other ABI.

### 1.2 Host files, then the adb install

Put the standalone installer in `$HOME/firerpa-nonroot`, outside `~/ops`. The three files
come from stayturgid commit
[7638f6e](https://github.com/djbclark/stayturgid/commit/7638f6e1dc847f820db61da40891cb43589025b1).
Long form:
[examples/firerpa-nonroot/README.md](https://github.com/djbclark/stayturgid/blob/7638f6e1dc847f820db61da40891cb43589025b1/examples/firerpa-nonroot/README.md).

Host tools: Bash, curl, OpenSSH, `just` 1.20+ (written for 1.59.0), Android platform tools,
`python3.14`, and [`uv`](https://docs.astral.sh/uv/). lamda declares `Requires-Python <3.15`.
On macOS: `brew install just android-platform-tools python@3.14 uv`.

```bash
mkdir -p "$HOME/firerpa-nonroot"
cd "$HOME/firerpa-nonroot"
ref=7638f6e1dc847f820db61da40891cb43589025b1
base="https://raw.githubusercontent.com/djbclark/stayturgid/${ref}/examples/firerpa-nonroot"
curl -fLO "${base}/justfile"
curl -fLO "${base}/.env.example"
curl -fLO "${base}/README.md"
cp .env.example .env
```

Write `.env` with the serial from 1.1 and these pins. `FIRERPA_HOST` is the address SSH and
gRPC use. Use the phone's Tailscale or LAN address when it has one. For a USB-only session,
set `FIRERPA_HOST=127.0.0.1` and add the `adb forward` in 1.5. Leave port 65000 off the
public internet.

```dotenv
ADB_TARGET=<serial from adb devices -l>
FIRERPA_HOST=<phone address, or 127.0.0.1 when using adb forward>
FIRERPA_ARCH=arm64-v8a
FIRERPA_PORT=65000
FIRERPA_PYTHON=python3.14
FIRERPA_VERSION=10.12
STAYTURGID_REF=3b6b618a4457e90c78f9181274ad6e7ab810c5c8
UPSTREAM_SOURCE_REF=e91a88f5e75793ca22a1349855ab0096e7d06ab3
```

`STAYTURGID_REF` is the stayturgid commit whose patcher lists 10.12
([3b6b618](https://github.com/djbclark/stayturgid/commit/3b6b618a4457e90c78f9181274ad6e7ab810c5c8)).
`UPSTREAM_SOURCE_REF` is the lamda source tag for `tools/cert.py`. Upstream release:
[v10.12](https://github.com/firerpa/lamda/releases/tag/v10.12). Arm64 fallback mirror:
[v10.12-binaries](https://github.com/djbclark/lamda/releases/tag/v10.12-binaries).

`just prepare` only builds host files: the verified server tarball, `certs/lamda.pem`, and
the patched driver `.work/aab.zip.patched`. `just install` is what changes the phone. It
runs the adb commands in 1.3 and 1.4, then checks 1.5. Run it. If you drive adb yourself,
run those commands in this order and do not skip the signed-start then patched-swap.

```bash
just config
just doctor
just prepare
just install
```

### 1.3 What adb pushes and how the device is configured

`ROOT=/data/local/tmp/firerpa`. Every `adb` call is `adb -s "$ADB_TARGET"`.

1. `adb push .work/lamda-server-$FIRERPA_ARCH.tar.gz /data/local/tmp/firerpa-server.tar.gz`
2. Over `adb shell`, stop any `lamda` process whose executable is under `$ROOT/server`,
   remove `$ROOT/server` and `/data/local/tmp/usr`, extract the tarball into `$ROOT`, and
   copy the signed `aab.zip` to `$ROOT/overrides/aab.zip.signed` (mode `0644`).
3. `adb push` the patched driver to `$ROOT/overrides/aab.zip.patched`,
   `firerpa_lifecycle.py` to `$ROOT/firerpa_lifecycle.py`, and `certs/lamda.pem` to
   `$ROOT/server/lamda.pem`.
4. Write `$ROOT/server/properties.local` and push it with adb. This is the server
   configuration: SSH on, FIRERPA's own adb off, cron off, forwarding off, mDNS off,
   WebRTC and remote-desktop video off.

```text
port=65000
[sshd]
sshd.enable=true
[adb]
adb.enable=false
[cron]
cron.enable=false
[fwd]
fwd.enable=false
[tunnel2]
tunnel2.enable=false
[mdns]
mdns.enable=false
[webui]
webui.darkmode=off
webui.audio=off
webui.webrtc=off
webui.video.h264=off
webui.share-clipboard=off
```

5. Over `adb shell`: `chmod 0600` the device `lamda.pem`, `0644` the patched driver and
   `properties.local`, `0755` the lifecycle script.

`certs/lamda.pem` is the gRPC client certificate and the SSH private identity. It holds an
unencrypted private key. Keep the host copy mode `0600`, back it up, and never commit it.
`adb.enable=false` turns off FIRERPA's embedded adb. It does not turn off Android's adbd.
On the Titan 2 Elite, adb over TCP 5555 stays as `qa/titan2-elite-facts.md` section 1
describes it.

### 1.4 Start over adb, certificate required

Start with the lifecycle helper, whose transport is `adb -s "$ADB_TARGET" shell`:

```bash
.work/venv/bin/python .work/firerpa_lifecycle.py start \
  --adb-target "$ADB_TARGET" \
  --root /data/local/tmp/firerpa \
  --port 65000 \
  --certificate /data/local/tmp/firerpa/server/lamda.pem
```

That adb session restores the signed `aab.zip`, then launches:

```text
cd /data/local/tmp/firerpa/server && nohup sh bin/launch.sh \
  --port=65000 --certificate=/data/local/tmp/firerpa/server/lamda.pem
```

It waits until port 65000 is listening, so FIRERPA's integrity check has accepted the
signed driver. It then swaps in `overrides/aab.zip.patched` and restarts only FIRERPA's
UIAutomation helper processes. A hand-written `python -m lamda` that starts on the patched
archive fails that check. A launch without `--certificate=` does not select `lamda.pem`.

The active driver sha256 for 10.12 must be
`ac0dcbf8d7528aae7770e8d8387152991d5a4690c45b37f9f8ab24a1a9139e2e`. An unvetted release
stops before the first `adb push`.

### 1.5 Prove it from adb, then open the client

```bash
adb -s "$ADB_TARGET" shell 'id -u; ss -ltn | grep ":65000 "; sha256sum /data/local/tmp/firerpa/server/lib/python3.*/site-packages/lamda/aab.zip'
adb -s "$ADB_TARGET" forward tcp:65000 tcp:65000
just ssh-id
just grpc
```

`id` prints `2000`. `ss` shows port 65000. The hash is the 10.12 patched driver.
`just ssh-id` logs in as `shell` with `ssh -i certs/lamda.pem -p 65000` and prints UID 2000.
`just grpc` prints `FIRERPA v10.12` from
`Device(FIRERPA_HOST, port=65000, certificate=certs/lamda.pem)`.

Use the forward when `FIRERPA_HOST` is `127.0.0.1`. Skip the forward when `FIRERPA_HOST`
is the phone's own address. After a reboot, repeat 1.4 with `just start` (it needs adb).
Removal is `just CONFIRM=YES uninstall`, which leaves the host certificate in place.

## 2. What the harness is

Hardware-in-the-loop QA for Plektra: a one-handed chording keyboard (ESP32-C6 / nRF52840,
BLE and USB HID) against Android. The prompt names a Pixel 7a. The same harness reads
`FIRERPA_HOST` and does not bake in a serial. Titan 2 Elite key facts stay in the other file.

Drive the phone with `lamda.client` over the certificate from section 1. Do not use Appium,
Maestro, or `openatx/uiautomator2`. Host tooling is `uv`, a `Justfile`, and a FastMCP server
named `plektra-qa-mcp`.

HID rule, for every recipe and tool: do not change the active `InputMethodService`, do not
rewrite the physical keyboard layout, and do not `am force-stop` or `am start -S` the Plektra
IME (`qa/titan2-elite-facts.md` section 1.3). The section 1 patch is what lets that IME keep
receiving chords while FIRERPA's UIAutomation is connected. `set_text` types through
FIRERPA, so a chord trial never calls it.

## 3. Package

Create `qa/plektra_qa/` as a uv project (`qa/pyproject.toml`). Dependencies: `lamda` at the
same version as `FIRERPA_VERSION`, `mcp` (CLI extra) or `fastmcp`, `pydantic`, `rich`,
`typer`, `pyyaml`. Python `>=3.12,<3.15`. Point the venv at `python3.14`.

Environment, read by every recipe:

```dotenv
FIRERPA_HOST=127.0.0.1
FIRERPA_PORT=65000
FIRERPA_CERT=/absolute/path/to/lamda.pem
ADB_TARGET=
```

`FIRERPA_CERT` is required. The section 1 server rejects an anonymous gRPC channel.
`ADB_TARGET` is set only when `qa-connect` must publish `adb forward`.

`qa/Justfile` recipes:

1. `just qa-connect`. If `ADB_TARGET` is set, run `adb -s "$ADB_TARGET" forward tcp:${FIRERPA_PORT} tcp:${FIRERPA_PORT}`. Open `Device(FIRERPA_HOST, port=FIRERPA_PORT, certificate=FIRERPA_CERT)`, call `server_info()`, and print version, uptime, and the round-trip time. Fail if `version` is not `FIRERPA_VERSION` (default `10.12`).
2. `just qa-prep`. Set the three global animator scales to `0` through `d.stub("Settings").put_global`: `window_animation_scale`, `transition_animation_scale`, `animator_duration_scale`. Register watchers with `register_click_target_selector_watcher` for the Bluetooth and system dialogs this phone actually shows, then `set_watcher_loop_enabled(True)`. A watcher may click a dialog button. It may not change the IME or take HID focus. Record the watcher names in this file when they are proven on a device.
3. `just qa-run suites/smoke_chords.yaml`. Run that suite in the terminal with a `rich` live table.
4. `just qa-mcp`. Serve `plektra-qa-mcp` on stdio for an agent.

## 4. Screen controller (`qa/plektra_qa/controller.py`)

Wrap one warm `Device`. `teleport_to_activity(package, activity, action=None, data_uri=None, extras=None)` calls:

```python
d.start_activity(
    package=package,
    component=f"{package}/{activity}",
    action=action,
    data=data_uri,
    extras=extras or {},
)
```

Omit `action` and `data` when the caller passed `None` (`ApplicationActivityRequest` uses `package`, `component`, `action`, `data`, `extras`). This is one gRPC call, the same shape as upstream `examples/activity_jump.py`.

`focus_and_clear(resource_id=None, text_hint=None, class_name="android.widget.EditText")`:

1. Prefer `d(resourceId=resource_id)` when `resource_id` is set, else `d(text=text_hint)` when `text_hint` is set, else `d(focused=True)`, else `d(className=class_name)`.
2. Read `element.info()`. Call `element.click()` only when `info.focused` is false.
3. Call `element.clear_text_field()`. The client method is `clear_text_field`, not `clear_textField`.
4. Read `element.get_text()` and continue only when it is empty.

Selector keyword `resourceId` matches `ObjInfo.resourceName` on the way back out.

## 5. HITL runner (`qa/plektra_qa/hitl_runner.py`)

No model call between chords. `run_chord_batch(trials)` polls the already-open gRPC channel every 25 ms.

Each trial:

1. Optional `teleport_to_activity` or a selector `click`, then `focus_and_clear`.
2. Print the prompt with `rich`: the string, chord sequence, or shortcut (Copy, Paste, layer switch). Cue the human with the terminal bell. Call `d.beep()` only when the trial sets `cue_on_device`.
3. Poll `element.get_text()`, or `d.get_clipboard()` when `verify_mode == "clipboard"`.
4. Record `time.perf_counter()`:
   1. `t_ready`: field focused and empty.
   2. `t_first_input`: first non-empty character.
   3. `t_complete`: target matched, or the sentinel (default `\n`, or a trailing space, or the trial's sentinel), or timeout.
5. Score pass or fail, the character diff, the gap since the previous trial, and WPM from `t_first_input` to `t_complete`. Advance within 50 ms. The human does not touch the host keyboard or mouse during the batch.

A trial is a pydantic model: `prompt`, `expect`, `verify_mode` (`text` or `clipboard`), `sentinel`, `timeout_s`, optional `package` / `activity` / `resource_id`, optional `cue_on_device`.

## 6. MCP server (`qa/plektra_qa/mcp_server.py`)

Tools:

1. `device_status()`. Round-trip time of `server_info()`, `device_info().displayWidth` and `displayHeight`, `current_application().applicationId` and `.activity`, `get_clipboard()`, `server_info().version`.
2. `teleport_and_prep_input(package, activity, field_resource_id)`. Section 4, then return the focused field's `info()`.
3. `inspect_ui_fast(include_screenshot_b64=False, jpeg_quality=75)`. `d.screenshot(quality=jpeg_quality)` returns a `BytesIO` of JPEG bytes; base64 it only when asked. Walk `d.dump_window_hierarchy()` and keep nodes that are clickable, focusable, focused, or have text or a resource id. Emit `resourceId` (from `resourceName` / `resource-id`), `text`, `className`, `bounds`, `focused`. Drop the rest.
4. `execute_ui_action(action, selector_kwargs)`. `action` is `click`, `long_click`, `clear_text_field`, or `press_key`. The first three run on `d(**selector_kwargs)`. `press_key` calls `d.press_key` with a `lamda.client.Keys` value. This tool has no `set_text`.
5. `request_human_chord_batch(trials_json)`. Parse the trials, run section 5, block until the batch finishes, and return one JSON object per trial: accuracy, diff, WPM, `t_ready`, `t_first_input`, `t_complete`, and the gap from the previous trial.

## 7. Sample suite (`qa/suites/smoke_chords.yaml`)

Two phases. The runner loads this file. It does not exist until the package is built.

```yaml
name: smoke_chords
timeout_s: 30
sentinel: "\n"
phases:
  - name: one_screen
    # One field. The human chords the whole list without a screen change.
    resource_id: null
    trials:
      - prompt: "alpha: hello"
        expect: "hello"
      - prompt: "digits: 42"
        expect: "42"
      - prompt: "punctuation: a, b."
        expect: "a, b."
      - prompt: "backspace the previous word, then type ok"
        expect: "ok"
  - name: two_fields
    trials:
      - prompt: "field A: alpha"
        expect: "alpha"
        package: null
        activity: null
        resource_id: null
      - prompt: "Tab or Enter on Plektra, then field B: beta"
        expect: "beta"
        resource_id: null
      - prompt: "Ctrl+A, Ctrl+C on Plektra"
        expect: "beta"
        verify_mode: clipboard
```

Fill `package`, `activity`, and `resource_id` from a real screen before treating a failure as a keyboard bug. Phase `one_screen` leaves them unset and uses the focused `EditText`.

## 8. Build order

1. Section 1, on the user's device, over adb. `adb shell` shows UID 2000 and the patched
   driver hash, and `just grpc` prints `FIRERPA v10.12`, before any harness code is trusted.
2. `qa/pyproject.toml`, `controller.py`, and a connect check that prints `server_info().version`.
3. `hitl_runner.py` and `suites/smoke_chords.yaml`, proven by one short batch with a person holding the keyboard.
4. `mcp_server.py` and `just qa-mcp`.
5. Write the watcher selectors from step 3 of section 3 back into this file with the date and the phone.
