#!/usr/bin/env python3
"""Drives TypeFacilitator on an Android emulator the way a person would: enables the keyboard,
taps keys, swipes words, opens the emoji, symbol and clipboard panels, switches to one-handed and
floating mode, and checks what arrives in a text field. Debug builds log where each key is on
screen (logcat tag TFKEYS); the script reads that instead of guessing coordinates.

Run with an emulator attached, from the TypeFacilitator folder: python3 scripts/emulator_test.py
Screenshots and logs go to emulator-out/.
"""
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PKG = "com.typefacilitator"
IME = PKG + "/.TfService"
DEBUG_APK = "app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK = "app/build/outputs/apk/release/app-release.apk"
OUT = "emulator-out"
HINT = "Try the keyboard here"
os.makedirs(OUT, exist_ok=True)
results = []
shot_n = [0]


def adb(*args, binary=False, timeout=120):
    r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode(errors="replace")


def sh(cmd, **kw):
    return adb("shell", cmd, **kw)


def log(msg):
    print(msg, flush=True)


def shot(name):
    shot_n[0] += 1
    fn = f"{OUT}/{shot_n[0]:02d}_{re.sub(r'[^A-Za-z0-9]+', '_', name)}.png"
    with open(fn, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True))
    return fn


API = int(sh("getprop ro.build.version.sdk").strip() or 0)


def nodes():
    for _ in range(5):
        out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml")
        i = out.find("<?xml")
        if i >= 0:
            try:
                return list(ET.fromstring(out[i:]).iter("node"))
            except ET.ParseError:
                pass
        time.sleep(1)
    return []


def bounds(n):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", n.get("bounds") or "")
    return tuple(map(int, m.groups())) if m else (0, 0, 0, 0)


def field():
    for n in nodes():
        if n.get("class") == "android.widget.EditText" and n.get("package") == PKG:
            return n
    return None


def text():
    n = field()
    if n is None:
        return None
    t = n.get("text") or ""
    return "" if t == HINT else t


def tap(x, y):
    sh(f"input tap {int(x)} {int(y)}")


def keymap(layout=None, need=None, timeout=8):
    """Latest logged key positions; waits until the layout (and a key) we expect is there."""
    end = time.time() + timeout
    last = {}
    while True:
        lines = [l for l in adb("logcat", "-d", "-s", "TFKEYS:I").splitlines() if "layout=" in l]
        if lines:
            body = lines[-1].split("TFKEYS", 1)[1]
            body = body.split(": ", 1)[1] if ": " in body else body
            m = {"_layout": re.search(r"layout=(\S+)", body).group(1)}
            for tok in body.split(" "):
                if tok.startswith("sugg="):
                    m["_sugg"] = [w.replace("_", " ") for w in tok[5:].split("|") if w]
                elif "@" in tok and re.search(r"@-?\d+,-?\d+$", tok):
                    name, pos = tok.rsplit("@", 1)
                    x, y = pos.split(",")
                    m[name] = (int(x), int(y))
                elif "=" in tok:
                    k, v = tok.split("=", 1)
                    m["_" + k] = v
            last = m
            if (layout is None or m["_layout"] == layout) and (need is None or need in m):
                return m
        if time.time() > end:
            return last
        time.sleep(0.4)


def press(name, layout=None):
    m = keymap(layout, need=name)
    if name not in m:
        raise RuntimeError(f"key {name!r} not on screen (layout {m.get('_layout')}, have {sorted(k for k in m if not k.startswith('_'))[:60]})")
    tap(*m[name])
    time.sleep(0.25)


def type_keys(s):
    m = keymap("letters", need="q")
    for ch in s:
        name = "SPACE" if ch == " " else ch
        if name not in m:
            m = keymap(need=name)
        if name not in m:
            raise RuntimeError(f"no key for {ch!r} in layout {m.get('_layout')}")
        tap(*m[name])
        time.sleep(0.12)
    time.sleep(0.5)


def clear_field():
    sh("input keyevent KEYCODE_MOVE_END")
    sh("input keyevent " + " ".join(["67"] * 80))
    time.sleep(0.8)


def focus_field():
    n = field()
    if n is None:
        raise RuntimeError("test field not found")
    x0, y0, x1, y1 = bounds(n)
    tap((x0 + x1) // 2, (y0 + y1) // 2)
    time.sleep(1.5)


def toolbar(name):
    """Taps a tool on the strip, opening the tool row first if words are being suggested."""
    m = keymap()
    if name not in m:
        press("TOGGLE")
        time.sleep(0.4)
    press(name)
    time.sleep(0.8)


def crashed():
    out = adb("logcat", "-d", "-s", "AndroidRuntime:E")
    i = out.find("Process: " + PKG)
    return out[max(0, i - 200):i + 3000] if i >= 0 else ""


def check(name, fn):
    log(f"--- {name}")
    try:
        detail = fn()
        c = crashed()
        if c:
            raise RuntimeError("app crashed:\n" + c)
        results.append((name, True, detail or ""))
        log(f"PASS {name} {detail or ''}")
    except Exception as e:
        c = crashed()
        msg = str(e) + ("\nCRASH:\n" + c if c and c not in str(e) else "")
        results.append((name, False, msg))
        log(f"FAIL {name}: {msg}")
        traceback.print_exc()
        shot("FAIL_" + name)
        adb("logcat", "-c")


def expect(got, want, what=""):
    if got != want:
        raise RuntimeError(f"{what} expected {want!r}, got {got!r}")
    return repr(got)


def enable_ime():
    """Turns the keyboard on and selects it; Android needs a moment after install to list it."""
    out = ""
    for _ in range(15):
        out = sh(f"ime enable {IME}") + sh(f"ime set {IME}")
        cur = sh("settings get secure default_input_method").strip()
        if cur == IME or cur.endswith("/com.typefacilitator.TfService"):
            return
        time.sleep(2)
    raise RuntimeError(f"could not select the keyboard: {out.strip()} / known: {sh('ime list -a -s').strip()}")


# ------------------------------------------------------------------ tests
def t_setup():
    out = adb("install", "-r", DEBUG_APK)
    if "Success" not in out:
        raise RuntimeError("install failed: " + out)
    perms = sh(f"dumpsys package {PKG} | grep -i 'android.permission' | sort -u")
    if "INTERNET" in perms:
        raise RuntimeError("APK asks for internet access: " + perms)
    enable_ime()
    sh("settings put secure show_ime_with_hard_keyboard 1")
    adb("logcat", "-c")
    sh(f"am start -W -n {PKG}/.SettingsActivity")
    time.sleep(3)
    shot("settings")
    focus_field()
    m = keymap("letters", need="q", timeout=20)
    if "q" not in m:
        raise RuntimeError("keyboard did not appear: " + str(m)[:300])
    time.sleep(2.5)  # positions are logged again once the keyboard has settled
    m = keymap("letters", need="q")
    shot("keyboard")
    return f"api {API}, {len(m)} keys, no internet permission"


def t_typing():
    clear_field()
    type_keys("hello world")
    shot("typing")
    return expect(text(), "Hello world", "auto-capitalised typing")


def t_autocorrect():
    type_keys(" teh ")
    expect(text(), "Hello world the ", "auto-correct")
    press("DEL")
    time.sleep(0.5)
    return expect(text(), "Hello world teh ", "backspace undoes the correction")


def t_suggestion():
    clear_field()
    type_keys("keyb")
    m = keymap("letters", need="SG0")
    words = m.get("_sugg", [])
    if not any(w.lower().startswith("keyboard") for w in words):
        raise RuntimeError(f"no 'keyboard' among suggestions {words}")
    shot("suggestions")
    i = [w.lower().startswith("keyboard") for w in words].index(True)
    picked = words[i]
    tap(*m[f"SG{i}"])
    time.sleep(0.6)
    expect(text(), picked + " ", "picked suggestion")
    # punctuation after a picked word closes up, then the next sentence is capitalised
    type_keys(".ok")
    return expect(text(), picked + ". Ok", "tidy punctuation and sentence capital")


def t_next_word():
    clear_field()
    type_keys("i am ")
    m = keymap("letters", need="SG0")
    words = m.get("_sugg", [])
    if not words:
        raise RuntimeError("no next-word guesses shown")
    tap(*m["SG0"])
    time.sleep(0.5)
    return expect(text(), "I am " + words[0] + " ", "next word guess")


def t_double_space():
    clear_field()
    type_keys("go  ")
    return expect(text(), "Go. ", "double space")


def t_shift_caps():
    clear_field()
    type_keys("a")
    x, y = keymap("letters", need="SHIFT")["SHIFT"]
    sh(f"input swipe {x} {y} {x} {y} 800")  # hold shift = caps lock (so is a double tap)
    time.sleep(0.4)
    type_keys("bc")
    press("SHIFT")
    type_keys("d")
    return expect(text(), "ABCd", "shift and caps lock")


def t_symbols():
    clear_field()
    press("SYM")
    m = keymap("symbols", need="@")
    for k in ["1", "2", "@", "#"]:
        tap(*m[k]); time.sleep(0.15)
    shot("symbols")
    press("SYM2")
    m = keymap("symbols2", need="€")
    tap(*m["€"]); time.sleep(0.2)
    shot("symbols2")
    press("ABC")
    keymap("letters", need="q")
    return expect(text(), "12@#€", "symbol pages")


def t_long_press():
    clear_field()
    m = keymap("letters", need="e")
    x, y = m["e"]
    sh(f"input swipe {x} {y} {x} {y} 900")
    time.sleep(0.5)
    expect(text(), "3", "long press on e gives the first alternative")
    # slide to an accent: hold, move right along the popup row, lift
    if API >= 31:
        sh(f"input motionevent DOWN {x} {y}; sleep 0.9; input motionevent MOVE {x + 150} {y - 150}; sleep 0.2; input motionevent UP {x + 150} {y - 150}")
        time.sleep(0.5)
        t = text()
        if len(t) != 2 or t[1] not in "èéêëēę3":
            raise RuntimeError(f"accent popup gave {t!r}")
        return repr(t)
    return "'3'"


def t_swipe():
    clear_field()
    m = keymap("letters", need="q")
    if API >= 31:
        pts = [m[c] for c in "helo"]
        cmds = [f"input motionevent DOWN {pts[0][0]} {pts[0][1]}"]
        for i in range(1, len(pts)):
            for t in range(1, 5):
                x = pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * t // 4
                y = pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * t // 4
                cmds.append(f"input motionevent MOVE {x} {y}")
            if i == 2:
                cmds.append("screencap -p /sdcard/trail.png")
        cmds.append(f"input motionevent UP {pts[-1][0]} {pts[-1][1]}")
        sh("; ".join(cmds), timeout=180)
        with open(f"{OUT}/swipe_trail.png", "wb") as f:
            f.write(adb("exec-out", "cat", "/sdcard/trail.png", binary=True))
        want = "Hello "
    else:
        # older Android can only inject a straight swipe: a -> s spells "as"
        (x0, y0), (x1, y1) = m["a"], m["s"]
        sh(f"input swipe {x0} {y0} {x1} {y1} 300")
        want = "As "
    time.sleep(1)
    shot("swiped")
    expect(text(), want, "swiped word")
    press("DEL")
    time.sleep(0.5)
    return expect(text(), "", "backspace removes the swiped word") + " after " + repr(want)


def t_emoji():
    clear_field()
    press("EMOJI")
    m = keymap("emoji", need="CELL0", timeout=15)
    shot("emoji")
    tap(*m["CELL0"]); time.sleep(0.4)
    tap(*m["CELL1"]); time.sleep(0.4)
    t = text()
    if t is None or len(t) < 2 or all(ord(c) < 0x2000 for c in t):
        raise RuntimeError(f"emoji not typed: {t!r}")
    n_items = int(m.get("_items", "0"))
    # another category, then the symbol catalog
    tap(*m["TAB6"]); time.sleep(0.8)
    m2 = keymap("emoji", need="CELL0")
    shot("emoji_category")
    tap(*m["TAB1"]); time.sleep(0.8)
    m3 = keymap("symbolgrid", need="CELL5")
    shot("symbol_catalog")
    tap(*m3["CELL5"]); time.sleep(0.4)
    t2 = text()
    if t2 == t:
        raise RuntimeError("symbol from catalog not typed")
    tap(*m3["TAB0"]); time.sleep(0.8)
    keymap("letters", need="q")
    return f"typed {t2!r}; first category has {n_items} emoji, category 4 has {m2.get('_items')}"


def t_clipboard():
    clear_field()
    type_keys("copy me")
    toolbar("TB_EDIT")
    m = keymap("edit", need="All")
    shot("edit_keys")
    tap(*m["All"]); time.sleep(0.5)
    tap(*m["Copy"]); time.sleep(0.8)
    tap(*m["RIGHT"]); time.sleep(0.3)
    tap(*m["ABC"]); time.sleep(0.6)
    type_keys(" ")
    toolbar("TB_CLIP")
    m = keymap("clipboard", need="CLIP0", timeout=10)
    shot("clipboard")
    if int(m.get("_clips", "0")) < 1:
        raise RuntimeError("copied text is not in the clipboard history")
    tap(*m["CLIP0"]); time.sleep(0.8)
    keymap("letters", need="q")
    return expect(text(), "Copy me Copy me", "tap on a clip types it")


def t_one_handed():
    m0 = keymap("letters", need="q")
    toolbar("TB_ONEHAND")
    m = keymap("letters", need="OH_SWITCH")
    if "OH_SWITCH" not in m:
        raise RuntimeError("one-handed mode did not turn on")
    shot("one_handed_right")
    clear_field()
    type_keys("ok")
    expect(text(), "Ok", "typing in one-handed mode")
    press("OH_SWITCH"); time.sleep(0.6)
    m2 = keymap("letters", need="OH_SWITCH")
    shot("one_handed_left")
    if not m2["q"][0] < m["q"][0]:
        raise RuntimeError(f"keyboard did not move to the left: q at {m['q']} then {m2['q']}")
    press("OH_EXIT"); time.sleep(0.6)
    m3 = keymap("letters", need="q")
    if "OH_SWITCH" in m3:
        raise RuntimeError("one-handed mode did not turn off")
    return f"q at x={m0['q'][0]} full, {m['q'][0]} right, {m2['q'][0]} left"


def t_floating():
    try:
        return floating_checks()
    finally:
        if "GRIP" in keymap():
            toolbar("TB_FLOAT")
            time.sleep(1)


def floating_checks():
    toolbar("TB_FLOAT")
    m = keymap("letters", need="GRIP")
    if "GRIP" not in m:
        raise RuntimeError("floating mode did not turn on")
    shot("floating")
    clear_field()
    type_keys("float")
    expect(text(), "Float", "typing on the floating keyboard")
    gx, gy = m["GRIP"]
    sh(f"input swipe {gx} {gy} {gx - 60} {gy - 500} 800")
    time.sleep(1.5)
    m2 = keymap("letters", need="GRIP")
    shot("floating_moved")
    if not m2["GRIP"][1] < gy - 200:
        raise RuntimeError(f"floating keyboard did not move: grip {m['GRIP']} then {m2['GRIP']}")
    type_keys("s")
    expect(text(), "Floats", "typing after moving")
    rx, ry = m2["RESIZE"]
    sh(f"input swipe {rx} {ry} {rx - 200} {ry} 600")
    time.sleep(1.5)
    m3 = keymap("letters", need="GRIP")
    shot("floating_resized")
    toolbar("TB_FLOAT")
    time.sleep(0.8)
    m4 = keymap("letters", need="q")
    if "GRIP" in m4:
        raise RuntimeError("floating mode did not turn off")
    return f"grip moved from y={gy} to y={m2['GRIP'][1]}; resize handle x {rx} -> {m3.get('RESIZE', ('?',))[0]}"


def t_landscape():
    sh("settings put system accelerometer_rotation 0")
    sh("settings put system user_rotation 1")
    time.sleep(4)
    try:
        if field() is not None:
            focus_field()
        m = keymap("letters", need="q", timeout=15)
        shot("landscape")
        clear_field()
        type_keys("wide")
        return expect(text(), "Wide", "typing in landscape")
    finally:
        sh("settings put system user_rotation 0")
        time.sleep(4)
        focus_field()


def find_row(label, max_scrolls=14):
    """Scrolls the settings list until a row with this text or description is on screen."""
    w, h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size")).groups())
    for _ in range(max_scrolls):
        for n in nodes():
            if label in (n.get("text") or "") or label in (n.get("content-desc") or ""):
                x0, y0, x1, y1 = bounds(n)
                if y1 > y0 and y0 > h * 0.15 and y1 < h * 0.95:
                    return (x0 + x1) // 2, (y0 + y1) // 2
        sh(f"input swipe {w // 2} {int(h * 0.75)} {w // 2} {int(h * 0.45)} 400")
        time.sleep(0.8)
    raise RuntimeError(f"settings row {label!r} not found")


def t_settings():
    sh("input keyevent KEYCODE_BACK")  # hide the keyboard
    time.sleep(1)
    x, y = find_row("Number row")
    shot("settings_layout")
    tap(x, y); time.sleep(0.5)
    x, y = find_row("theme White")
    tap(x, y); time.sleep(0.8)
    shot("settings_look")
    x, y = find_row("Learned words")
    shot("settings_data")
    tap(x, y); time.sleep(1)
    shot("learned_words")
    sh("input keyevent KEYCODE_BACK"); time.sleep(0.5)
    w, h = map(int, re.search(r"(\d+)x(\d+)", sh("wm size")).groups())
    for _ in range(12):
        sh(f"input swipe {w // 2} {int(h * 0.4)} {w // 2} {int(h * 0.8)} 250")
    time.sleep(0.5)
    focus_field()
    m = keymap("letters", need="1", timeout=15)
    if "1" not in m:
        raise RuntimeError("number row did not appear after switching it on")
    shot("white_theme_number_row")
    clear_field()
    type_keys("a1")
    return expect(text(), "A1", "number row and white theme")


def t_release():
    """The APK people install is the minified release build: make sure it starts and types too."""
    m = keymap("letters", need="q")
    sh("input keyevent KEYCODE_HOME")
    out = adb("uninstall", PKG) + adb("install", "-r", RELEASE_APK)
    if "Success" not in out:
        raise RuntimeError("release install failed: " + out)
    enable_ime()
    adb("logcat", "-c")
    sh(f"am start -W -n {PKG}/.SettingsActivity")
    time.sleep(3)
    focus_field()
    time.sleep(2)
    shot("release_build")
    # same screen, same default layout as the first debug run: reuse those key positions
    for ch in "hi there":
        tap(*first_map["SPACE" if ch == " " else ch]); time.sleep(0.15)
    time.sleep(1)
    shot("release_typed")
    return expect(text(), "Hi there", "typing on the release build")


first_map = {}


def main():
    check("install and enable", t_setup)
    if not results[-1][1]:
        with open(f"{OUT}/logcat.txt", "w") as f:
            f.write(adb("logcat", "-d"))
        with open(f"{OUT}/report.txt", "w") as f:
            f.write(f"Android API {API}: could not start\nFAIL  {results[-1][0]}  {results[-1][2]}\n")
        return 1
    first_map.update(keymap("letters", need="q"))
    for name, fn in [
        ("typing and auto-capitalise", t_typing), ("auto-correct and undo", t_autocorrect),
        ("suggestions and punctuation", t_suggestion), ("next-word guess", t_next_word),
        ("double space", t_double_space), ("shift and caps lock", t_shift_caps), ("symbols", t_symbols),
        ("long press", t_long_press), ("swipe typing", t_swipe), ("emoji and symbol catalog", t_emoji),
        ("clipboard history", t_clipboard), ("one-handed mode", t_one_handed), ("floating keyboard", t_floating),
        ("landscape", t_landscape), ("settings, theme, number row", t_settings), ("release build", t_release),
    ]:
        check(name, fn)
    with open(f"{OUT}/logcat.txt", "w") as f:
        f.write(adb("logcat", "-d"))
    bad = [r for r in results if not r[1]]
    lines = [f"Android API {API}: {len(results) - len(bad)}/{len(results)} checks passed"]
    lines += [f"{'PASS' if ok else 'FAIL'}  {name}  {detail}" for name, ok, detail in results]
    report = "\n".join(lines)
    with open(f"{OUT}/report.txt", "w") as f:
        f.write(report + "\n")
    log("=" * 60 + "\n" + report)
    return len(bad)


if __name__ == "__main__":
    sys.exit(main())
