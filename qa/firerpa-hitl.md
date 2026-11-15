# FIRERPA setup and the HITL QA harness

Fork-only (never in an upstream PR). Incorporates `~/Downloads/plektra-qa.txt` (2026-11-15).
That prompt assumed FireRPA was already running. This file is the setup that comes first,
then the harness that prompt described. API names below are the lamda 10.12 client
(`Device`, `Selector`, `clear_text_field`), checked against
[djbclark/lamda](https://github.com/djbclark/lamda/commit/e91a88f5e75793ca22a1349855ab0096e7d06ab3).

The Python package is specified here and is not in the tree yet. Build it from sections 3–7
in this worktree. Phone facts for the Titan 2 Elite stay in `qa/titan2-elite-facts.md`.

## 1. Install FIRERPA outside ~/ops

Copy the standalone installer into its own directory and run it there. This path does not
use a stayturgid checkout, the Ansible role, or a fleet converge. It also does not use
Termux, Magisk, or the FIRERPA APK. The phone stays unrooted (adb shell UID 2000).

The installer is three files from stayturgid commit
[7638f6e](https://github.com/djbclark/stayturgid/commit/7638f6e1dc847f820db61da40891cb43589025b1).
Its own README is the long form:
[examples/firerpa-nonroot/README.md](https://github.com/djbclark/stayturgid/blob/7638f6e1dc847f820db61da40891cb43589025b1/examples/firerpa-nonroot/README.md).

Host tools: Bash, curl, OpenSSH, `just` 1.20+ (the installer is written for 1.59.0),
Android platform tools, `python3.14`, and [`uv`](https://docs.astral.sh/uv/). lamda declares
`Requires-Python <3.15`. On macOS: `brew install just android-platform-tools python@3.14 uv`.

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

`.env` needs the phone plus the pins that match the vetted 10.12 driver. `ADB_TARGET` is
what `adb -s` accepts (USB serial or `host:port`). `FIRERPA_HOST` is the address SSH and
gRPC use. They can differ. A Tailscale address is the right `FIRERPA_HOST` when the phone
has one. Leave port 65000 off the public internet.

```dotenv
ADB_TARGET=YOUR_ADB_SERIAL
FIRERPA_HOST=YOUR_DEVICE_IP_OR_DNS_NAME
FIRERPA_ARCH=arm64-v8a
FIRERPA_PORT=65000
FIRERPA_PYTHON=python3.14
FIRERPA_VERSION=10.12
STAYTURGID_REF=3b6b618a4457e90c78f9181274ad6e7ab810c5c8
UPSTREAM_SOURCE_REF=e91a88f5e75793ca22a1349855ab0096e7d06ab3
```

`STAYTURGID_REF` is the stayturgid commit whose patcher lists 10.12
([3b6b618](https://github.com/djbclark/stayturgid/commit/3b6b618a4457e90c78f9181274ad6e7ab810c5c8)).
`UPSTREAM_SOURCE_REF` is the lamda source tag for `tools/cert.py`. Pixel 7a and the Titan 2
Elite are `arm64-v8a`. A 32-bit device sets `FIRERPA_ARCH=armeabi-v7a`; the arm64 mirror
does not cover that ABI.

```bash
just config
just doctor
just install
just status
just ssh-id
just grpc
```

`just doctor` checks that adb sees the device as UID 2000 and that the ABI matches.
`just install` resolves the pinned release, checks the published checksums, builds a
private `certs/lamda.pem`, patches the accessibility driver, and starts the server under
`/data/local/tmp/firerpa`. For arm64, a missing upstream asset falls back to the mirror
[v10.12-binaries](https://github.com/djbclark/lamda/releases/tag/v10.12-binaries). Upstream
release: [v10.12](https://github.com/firerpa/lamda/releases/tag/v10.12).

`just ssh-id` must print UID 2000 for user `shell`. `just grpc` must print `FIRERPA v10.12`.
`just status` must show the active `aab.zip` sha256
`ac0dcbf8d7528aae7770e8d8387152991d5a4690c45b37f9f8ab24a1a9139e2e`. That hash is the vetted
10.12 accessibility-coexistence driver (checked 2026-11-15 by disassembly and by `just patch`
on the host). An unvetted release stops before the device is touched.

`certs/lamda.pem` is the gRPC client certificate and the SSH private identity. It is mode
`0600`, it holds an unencrypted private key, and it is never committed. Back it up. Pass
`FIRERPA_CERTIFICATE` to reuse a cert that already exists. `just clean` deletes downloads
and keeps `certs/`.

The installed `properties.local` turns off FIRERPA's own adb, cron, forwarding, mDNS, and
WebRTC, and leaves SSH on. Android's adbd is a different daemon. On the Titan 2 Elite, adb
over TCP 5555 stays as `qa/titan2-elite-facts.md` section 1 describes it.

After a reboot the server is down until `just start`, which needs adb. Removal is
`just CONFIRM=YES uninstall`, which leaves the host certificate in place.

A USB-only gRPC check, with no phone address, is `adb -s "$ADB_TARGET" forward tcp:65000 tcp:65000`
and then `Device("127.0.0.1", port=65000, certificate=...)`. On the network, pass
`FIRERPA_HOST` and skip the forward.

This document's install has been checked as a host-side patch and as the published
installer. It has not been run onto a phone from this file.

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

1. Section 1 on the phone that will run the suite. `just grpc` prints `FIRERPA v10.12` before any harness code is trusted.
2. `qa/pyproject.toml`, `controller.py`, and a connect check that prints `server_info().version`.
3. `hitl_runner.py` and `suites/smoke_chords.yaml`, proven by one short batch with a person holding the keyboard.
4. `mcp_server.py` and `just qa-mcp`.
5. Write the watcher selectors from step 3 of section 3 back into this file with the date and the phone.
