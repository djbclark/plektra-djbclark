"""Naming keys in QA instructions.

On the Titan 2 Elite a key goes through three layers: the printed keycap, the
vendor remap (com.agui.shortcutsettings) and Plektra's own remap
(HardwareKeyRemap). "Ctrl+A" alone is ambiguous, so every key instruction a
person sees or hears comes in two versions:

1. the "your" version, `your Ctrl`: whichever key does Ctrl after both remaps;
2. a keycaps version, `Keycaps: hold Fn, tap A.`: what to press by printed
   glyph alone.

Suite text writes keys as tokens: `<Ctrl+A>`, `<Tab>`, `<Shift+Tab>`;
`<Tab|Enter>` means either. A sequence is separate tokens. `render` expands
the tokens and appends one keycaps line. A token that no keycap can produce
is an error (`KeyResolutionError`); there is never a single-version fallback.
`lint` rejects bare key names outside tokens.

The keymap comes from the phone when `ADB_TARGET` is set (vendor layer from
`getprop`, Plektra's layer from its prefs through `run-as`; read-only), else
from a profile in `qa/keymaps/`. The live read wins when they disagree.
"""

from __future__ import annotations

import html
import json
import os
import re
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

import yaml

DEFAULT_PACKAGE = "rocks.pkb.plektra.djb"
PROFILE_DIR = Path(__file__).resolve().parents[1] / "keymaps"
DEFAULT_PROFILE = PROFILE_DIR / "titan2-elite-baseline.yaml"
PREFS_PATH = "shared_prefs/plektra_prefs.xml"
TEXT_FIELDS = ("prompt", "shade", "speak")


class KeyNamingError(ValueError):
    """Suite text breaks the naming convention (bare key name, bad token)."""


class KeyResolutionError(ValueError):
    """A token names a key that nothing on this keyboard produces."""


class LiveReadError(RuntimeError):
    """The keymap could not be read from the phone."""


# --- functions -------------------------------------------------------------

FUNCTIONS = (
    "Ctrl", "Shift", "Alt", "Meta", "Sym", "Fn", "Tab", "Enter", "Backspace",
    "Delete", "Esc", "Space", "Back", "Home", "Recent", "Media",
)
MODIFIERS = frozenset({"Ctrl", "Shift", "Alt", "Meta", "Sym", "Fn"})
_ALIASES = {"control": "Ctrl", "return": "Enter", "del": "Delete", "escape": "Esc"}
FUNCTION_BY_LOWER = {name.lower(): name for name in FUNCTIONS} | _ALIASES

# Android key code -> function name (left and right keys are one function).
KEYCODE_FUNCTION = {
    113: "Ctrl", 114: "Ctrl", 59: "Shift", 60: "Shift", 57: "Alt", 58: "Alt",
    117: "Meta", 118: "Meta", 63: "Sym", 61: "Tab", 66: "Enter", 160: "Enter",
    67: "Backspace", 112: "Delete", 111: "Esc", 62: "Space", 4: "Back",
    3: "Home", 187: "Recent", 85: "Media",
}
# A function name written in a profile stands for this key code.
FUNCTION_KEYCODE = {
    "Ctrl": 113, "Shift": 59, "Alt": 58, "Meta": 117, "Sym": 63, "Tab": 61,
    "Enter": 66, "Backspace": 67, "Delete": 112, "Esc": 111, "Space": 62,
    "Back": 4, "Home": 3, "Recent": 187, "Media": 85,
}
SPOKEN_FUNCTION = {"Ctrl": "Control", "Esc": "Escape", "Fn": "F N", "Sym": "Sym"}


class Flavor(str, Enum):
    SCREEN = "screen"   # full text, glyphs allowed
    SHADE = "shade"     # screen text, keycaps compact (the phone's notification)
    SPOKEN = "spoken"   # words only, for the voice


# --- tokens ----------------------------------------------------------------

TOKEN_RE = re.compile(r"<([A-Za-z0-9][A-Za-z0-9+|]*)>")


@dataclass(frozen=True)
class Chord:
    mods: tuple[str, ...]
    key: str  # a function name, or one letter/digit (upper case)

    @property
    def is_char(self) -> bool:
        return len(self.key) == 1


@dataclass(frozen=True)
class Token:
    raw: str  # as written, with the angle brackets
    start: int
    end: int
    alternatives: tuple[Chord, ...]


def _parse_chord(text: str, token: str) -> Chord:
    parts = text.split("+")
    names: list[str] = []
    for index, part in enumerate(parts):
        last = index == len(parts) - 1
        if not part:
            raise KeyNamingError(f"empty key name in {token}")
        function = FUNCTION_BY_LOWER.get(part.lower())
        if function is not None:
            names.append(function)
        elif last and len(part) == 1:
            names.append(part.upper())
        else:
            raise KeyNamingError(
                f"unknown key name {part!r} in {token}; use one of "
                f"{', '.join(FUNCTIONS)}, or a single letter or digit last"
            )
    *mods, key = names
    for mod in mods:
        if mod not in MODIFIERS:
            raise KeyNamingError(f"{mod} in {token} is not a modifier; only the last key may be {mod}")
    if len(set(mods)) != len(mods):
        raise KeyNamingError(f"repeated modifier in {token}")
    return Chord(tuple(mods), key)


def find_tokens(text: str) -> list[Token]:
    tokens = []
    for match in TOKEN_RE.finditer(text):
        chords = tuple(_parse_chord(alt, match.group(0)) for alt in match.group(1).split("|"))
        tokens.append(Token(match.group(0), match.start(), match.end(), chords))
    return tokens


# --- keymap ----------------------------------------------------------------


@dataclass(frozen=True)
class Keycap:
    id: str
    screen: str
    spoken: str
    native_code: int | None
    native_function: str
    scan: int | None = None
    vendor: str | None = None
    where: str | None = None


@dataclass(frozen=True)
class Rule:
    """One Plektra remap rule: a scan rule beats a key rule (HardwareKeyRemap)."""

    match: str  # "scan" or "key"
    to: int
    scan: int | None = None
    key: int | None = None


@dataclass
class Keymap:
    source: str
    caps: tuple[Keycap, ...]
    vendor: dict[str, int] = field(default_factory=dict)  # keycap id -> typed key code
    rules: tuple[Rule, ...] = ()
    name: str = ""
    remap_profile: str = ""

    def function_of(self, cap: Keycap) -> str:
        """What the key does after the vendor layer and Plektra's layer."""
        code = self.vendor.get(cap.id) or cap.native_code
        target = None
        if cap.scan is not None:
            target = next((r.to for r in self.rules if r.match == "scan" and r.scan == cap.scan), None)
        if target is None and code is not None:
            target = next((r.to for r in self.rules if r.match == "key" and r.key == code), None)
        if target is not None:
            code = target
        if code is None:
            return cap.native_function
        return KEYCODE_FUNCTION.get(code, f"key code {code}")

    def functions(self) -> dict[str, str]:
        return {cap.id: self.function_of(cap) for cap in self.caps}

    def resolve(self, function: str) -> Keycap | None:
        """The keycap that does `function`; a key that is it by factory comes first."""
        found = [(index, cap) for index, cap in enumerate(self.caps) if self.function_of(cap) == function]
        found.sort(key=lambda item: (_native_function(item[1]) != function, item[0]))
        return found[0][1] if found else None

    def differences(self, other: Keymap) -> list[str]:
        mine, theirs = self.functions(), other.functions()
        return [
            f"{cap.screen} acts as {mine[cap.id]} (profile: {theirs.get(cap.id, '?')})"
            for cap in self.caps
            if mine[cap.id] != theirs.get(cap.id)
        ]


def _native_function(cap: Keycap) -> str:
    return KEYCODE_FUNCTION.get(cap.native_code, cap.native_function) if cap.native_code else cap.native_function


def _code(value: Any, where: str) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and FUNCTION_BY_LOWER.get(value.lower()) in FUNCTION_KEYCODE:
        return FUNCTION_KEYCODE[FUNCTION_BY_LOWER[value.lower()]]
    raise ValueError(f"{where}: expected a key code or a function name, got {value!r}")


def _rule(raw: Mapping[str, Any], where: str) -> Rule | None:
    match = raw.get("match")
    if match == "scan" and isinstance(raw.get("scan"), int):
        return Rule("scan", _code(raw.get("to"), where), scan=raw["scan"], key=raw.get("key"))
    if match == "key" and raw.get("key") is not None:
        return Rule("key", _code(raw.get("to"), where), key=_code(raw["key"], where))
    return None  # the app drops an invalid rule too


def load_profile(path: Path) -> Keymap:
    try:
        data = yaml.safe_load(path.read_text())
    except OSError as exc:
        raise ValueError(f"key profile not readable: {path}: {exc}") from exc
    if not isinstance(data, dict) or not data.get("keycaps"):
        raise ValueError(f"key profile {path} has no keycaps")
    caps = []
    for raw in data["keycaps"]:
        native = raw.get("native")
        code = native if isinstance(native, int) else None
        function = KEYCODE_FUNCTION.get(code, f"key code {code}") if code is not None else str(native)
        caps.append(
            Keycap(
                id=str(raw["id"]), screen=str(raw["screen"]), spoken=str(raw["spoken"]),
                native_code=code, native_function=function, scan=raw.get("scan"),
                vendor=raw.get("vendor"), where=raw.get("where"),
            )
        )
    ids = {cap.id for cap in caps}
    vendor = {}
    for cap_id, value in (data.get("vendor") or {}).items():
        if cap_id not in ids:
            raise ValueError(f"key profile {path}: vendor entry {cap_id!r} is not a keycap")
        code = _code(value, f"{path} vendor.{cap_id}")
        if code:
            vendor[cap_id] = code
    rules = tuple(
        rule for item in (data.get("plektra_remap") or []) if (rule := _rule(item, str(path))) is not None
    )
    name = str(data.get("name") or path.stem)
    return Keymap(
        source=f"profile {name}", caps=tuple(caps), vendor=vendor, rules=rules,
        name=name, remap_profile=str(data.get("remap_profile", "")),
    )


# --- live read (read-only adb) ----------------------------------------------

Runner = Callable[[list[str]], "subprocess.CompletedProcess[str]"]
_PROP_RE = re.compile(r"^\[persist\.sys\.agui_programmable_([a-z0-9_]+)_key\]: \[(-?\d+)\]\s*$", re.M)


def _run(argv: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(argv, capture_output=True, text=True, check=False, timeout=30)


def read_vendor_props(serial: str, run: Runner = _run) -> dict[str, int]:
    """`getprop` on the phone: vendor key name -> key code (0 = the key is native)."""
    result = run(["adb", "-s", serial, "shell", "getprop"])
    if result.returncode != 0 or "[" not in result.stdout:
        raise LiveReadError(f"adb getprop failed: {result.stderr.strip() or 'no output'}")
    # The key name is lazy-matched so `shift_l_key` gives `shift_l`.
    return {name: int(value) for name, value in _PROP_RE.findall(result.stdout)}


def rules_from_prefs(xml: str, remap_profile: str) -> tuple[Rule, ...] | None:
    """Plektra's rules for this keyboard, from its prefs XML; None when unreadable."""
    match = re.search(r'<string name="hardware_key_remaps">(.*?)</string>', xml, re.S)
    if match:
        try:
            doc = json.loads(html.unescape(match.group(1)))
        except json.JSONDecodeError:
            return None
        if not isinstance(doc, dict) or int(doc.get("v", 0)) > 1:
            return None  # a newer format: the app would not read it either
        stored = (doc.get("profiles") or {}).get(remap_profile.lower()) or {}
        rules: list[Rule] = []
        for raw in stored.get("rules") or []:
            if isinstance(raw, dict) and (rule := _rule(raw, "plektra prefs")) is not None:
                rules.append(rule)
        return tuple(rules)
    # Older builds: one boolean for the only remap there was.
    if re.search(r'<boolean name="key_remap_swap_shift_right_enter" value="true"', xml):
        return (Rule("scan", 66, scan=54, key=60), Rule("key", 60, key=66))
    return ()


def read_plektra_rules(
    serial: str, remap_profile: str, package: str = DEFAULT_PACKAGE, run: Runner = _run
) -> tuple[Rule, ...] | str:
    """The rules, or the reason they could not be read (a debuggable build is needed)."""
    result = run(["adb", "-s", serial, "exec-out", "run-as", package, "cat", PREFS_PATH])
    if result.returncode != 0 or "<map" not in result.stdout:
        return (result.stderr.strip() or result.stdout.strip() or "no output")[:120]
    rules = rules_from_prefs(result.stdout, remap_profile)
    return rules if rules is not None else "the stored remap rules could not be parsed"


def read_live(
    serial: str, base: Keymap, package: str = DEFAULT_PACKAGE, run: Runner = _run
) -> tuple[Keymap, list[str]]:
    """The phone's keymap on top of `base`'s keycap table, and notes on what was not readable."""
    props = read_vendor_props(serial, run)
    notes: list[str] = []
    vendor = {
        cap.id: props[cap.vendor] for cap in base.caps if cap.vendor in props and props[cap.vendor] != 0
    }
    known = {cap.vendor for cap in base.caps if cap.vendor}
    unknown = sorted(set(props) - known)
    if unknown:
        notes.append(f"vendor keys not in the profile's keycap table, ignored: {', '.join(unknown)}")
    rules = read_plektra_rules(serial, base.remap_profile, package, run)
    if isinstance(rules, str):
        notes.append(f"Plektra remap layer not readable ({rules}); the profile's rules are used")
        layers, rules = "vendor layer from the phone, Plektra remap from the profile", base.rules
    else:
        layers = "vendor layer and Plektra remap layer, both from the phone"
    keymap = Keymap(
        source=f"live ({layers})", caps=base.caps, vendor=vendor, rules=rules,
        name=base.name, remap_profile=base.remap_profile,
    )
    return keymap, notes


@dataclass
class Resolved:
    keymap: Keymap
    profile: Keymap
    notes: list[str] = field(default_factory=list)

    def describe(self) -> str:
        lines = [f"keymap: {self.keymap.source}"]
        if self.keymap is not self.profile:
            diff = self.keymap.differences(self.profile)
            if diff:
                lines.append(
                    f"keymap: live differs from {self.profile.source}; live wins: " + "; ".join(diff)
                )
        lines += [f"keymap: {note}" for note in self.notes]
        return "\n".join(lines)


def resolve_keymap(
    env: Mapping[str, str] | None = None,
    run: Runner = _run,
    on_live_failure: str = "fallback",
) -> Resolved:
    """Live from the phone when ADB_TARGET is set, else the profile.

    `PLEKTRA_QA_KEYMAP` picks the profile file; `PLEKTRA_QA_KEYMAP_SOURCE` is
    `auto` (default), `live` or `profile`. When the live read fails,
    `on_live_failure` is "fallback" (use the profile, say so) or "error".
    """
    env = os.environ if env is None else env
    path = Path(env.get("PLEKTRA_QA_KEYMAP", "").strip() or DEFAULT_PROFILE).expanduser()
    profile = load_profile(path)
    source = env.get("PLEKTRA_QA_KEYMAP_SOURCE", "auto").strip().lower() or "auto"
    if source not in ("auto", "live", "profile"):
        raise ValueError(f"PLEKTRA_QA_KEYMAP_SOURCE must be auto, live or profile: {source}")
    serial = env.get("ADB_TARGET", "").strip()
    if source == "profile" or (source == "auto" and not serial):
        return Resolved(profile, profile)
    if not serial:
        raise LiveReadError("PLEKTRA_QA_KEYMAP_SOURCE=live needs ADB_TARGET")
    package = env.get("PLEKTRA_QA_PACKAGE", "").strip() or DEFAULT_PACKAGE
    try:
        keymap, notes = read_live(serial, profile, package, run)
    except (LiveReadError, subprocess.SubprocessError, OSError) as exc:
        if on_live_failure == "error" or source == "live":
            raise LiveReadError(f"live keymap read failed: {exc}") from exc
        return Resolved(profile, profile, [f"live read failed ({exc}); using the profile"])
    return Resolved(keymap, profile, notes)


# --- lint ------------------------------------------------------------------

BARE_KEY_NAMES = (
    "Ctrl", "Control", "Alt", "Shift", "Fn", "Sym", "Tab", "Enter", "Return", "Backspace",
    "Del", "Delete", "Esc", "Escape", "Meta", "Home", "Back", "Recent",
)
# Ordinary phrases that contain a key name. Keep this small: add a phrase here
# rather than weakening the rule.
ALLOWED_PHRASES = (
    "home screen", "home page", "back to", "back and forth", "come back", "comes back",
    "came back", "read back", "return to", "browser tab", "new tab",
)
_NAME_RE = re.compile(r"\b(" + "|".join(BARE_KEY_NAMES) + r")\b((?:\s*\+\s*[A-Za-z0-9]+)*)", re.I)
_ALLOWED_RE = re.compile(r"\b(?:" + "|".join(ALLOWED_PHRASES) + r")\b", re.I)


def lint(text: str) -> list[tuple[str, str]]:
    """Bare key names outside tokens: (name as written, the token to write instead)."""
    plain = _ALLOWED_RE.sub(lambda m: " " * len(m.group(0)), TOKEN_RE.sub(lambda m: " " * len(m.group(0)), text))
    found = []
    for match in _NAME_RE.finditer(plain):
        chord = re.sub(r"\s*\+\s*", "+", match.group(0).strip())
        found.append((match.group(1), f"<{chord}>"))
    return found


def check_naming(text: str, where: str) -> None:
    """Raise `KeyNamingError` for bare key names; `where` names the field."""
    for name, suggestion in lint(text):
        raise KeyNamingError(
            f"{where}: bare key name {name!r} in {text!r}. Write keys as tokens, for example "
            f"{suggestion} (see qa/README.md, 'Naming keys')."
        )
    find_tokens(text)  # a malformed token is an error too


# --- rendering -------------------------------------------------------------


def _your(chord: Chord, flavor: Flavor) -> str:
    if flavor is Flavor.SPOKEN:
        names = [f"your {SPOKEN_FUNCTION.get(m, m)}" for m in chord.mods]
        if chord.is_char:
            names.append(f"{'digit' if chord.key.isdigit() else 'letter'} {chord.key}")
        else:
            names.append(f"your {SPOKEN_FUNCTION.get(chord.key, chord.key)}")
        return " plus ".join(names)
    names = [f"your {m}" for m in chord.mods]
    names.append(chord.key if chord.is_char else f"your {chord.key}")
    return " + ".join(names)


class _KeycapWriter:
    """Writes keycap phrases; gives a keycap's place hint once per text."""

    def __init__(self, keymap: Keymap, flavor: Flavor, context: str) -> None:
        self.keymap, self.flavor, self.context = keymap, flavor, context
        self.placed: set[str] = set()

    def cap(self, function: str, token: str) -> str:
        cap = self.keymap.resolve(function)
        if cap is None:
            raise KeyResolutionError(
                f"{self.context}key token {token} cannot be resolved to a keycap: nothing on this "
                f"keyboard acts as {function} ({self.keymap.source}). Change the suite or the keymap."
            )
        spoken = self.flavor is Flavor.SPOKEN
        name = cap.spoken if spoken else cap.screen
        if cap.where and self.flavor is not Flavor.SHADE and cap.id not in self.placed:
            self.placed.add(cap.id)
            name = f"{name}, {cap.where}" if spoken else f"{name} ({cap.where})"
        return name

    def chord(self, chord: Chord, token: str) -> str:
        mods = [self.cap(m, token) for m in chord.mods]
        key = chord.key if chord.is_char else self.cap(chord.key, token)
        if self.flavor is Flavor.SHADE:
            return "+".join([*mods, key])
        if chord.is_char and self.flavor is Flavor.SPOKEN:
            key = f"{'digit' if key.isdigit() else 'letter'} {key}"
        if not mods:
            return f"tap {key}"
        return f"hold {' and '.join(mods)}, tap {key}"

    def line(self, tokens: list[Token]) -> str:
        parts = [
            " or ".join(self.chord(chord, token.raw) for chord in token.alternatives)
            for token in tokens
        ]
        if self.flavor is Flavor.SPOKEN:
            body = ". Then ".join(parts)
            return f"By keycaps, {body}."
        joiner = "; then " if self.flavor is Flavor.SCREEN else ", then "
        return f"Keycaps: {joiner.join(parts)}."


def render(text: str, keymap: Keymap, flavor: Flavor = Flavor.SCREEN, context: str = "") -> str:
    """Expand the key tokens in `text` and append one keycaps line.

    Text without tokens comes back unchanged. `context` prefixes error messages.
    """
    context = f"{context}: " if context else ""
    try:
        tokens = find_tokens(text)
    except KeyNamingError as exc:
        raise KeyNamingError(f"{context}{exc}") from exc
    if not tokens:
        return text
    out, last = [], 0
    for token in tokens:
        out.append(text[last:token.start])
        out.append(" or ".join(_your(chord, flavor) for chord in token.alternatives))
        last = token.end
    out.append(text[last:])
    body = "".join(out)
    if body.startswith("your "):
        body = "Y" + body[1:]
    body = body.rstrip()
    if body and body[-1] not in ".!?":
        body += "."
    return f"{body} {_KeycapWriter(keymap, flavor, context).line(tokens)}"


def localise_texts(fields: Mapping[str, Any], keymap: Keymap, where: str) -> dict[str, Any]:
    """Lint a trial's prompt/shade/speak and expand their key tokens.

    A trial with tokens gets `prompt` (screen), `shade` (compact) and `speak`
    (words only) all filled in; a trial without keeps its fields as written.
    """
    out = dict(fields)
    for name in TEXT_FIELDS:
        value = fields.get(name)
        if isinstance(value, str):
            check_naming(value, f"{where}, field '{name}'")
    texts = [fields.get(name) for name in TEXT_FIELDS]
    if not any(isinstance(t, str) and find_tokens(t) for t in texts):
        return out
    prompt = str(fields.get("prompt", ""))
    shade, speak = fields.get("shade"), fields.get("speak")
    for field_name, source, flavor in (
        ("prompt", prompt, Flavor.SCREEN),
        ("shade", shade if shade is not None else prompt, Flavor.SHADE),
        ("speak", speak or shade or prompt, Flavor.SPOKEN),
    ):
        out[field_name] = render(source, keymap, flavor, f"{where}, field '{field_name}'")
    return out
