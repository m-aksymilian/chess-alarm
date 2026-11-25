"""Synthesise the app's original, royalty-free alarm tones and board sounds.

Usage: python3 tools/make_sounds.py app/src/main/res/raw   (needs numpy + ffmpeg)
Every sound is generated from scratch here, so the app ships no third-party audio.
"""
import os, subprocess, sys, tempfile, wave
import numpy as np

SR = 44100
out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)

def t(sec):
    return np.arange(int(SR * sec)) / SR

def env(n, a=0.01, r=0.3):
    e = np.ones(n)
    ai, ri = int(SR * a), int(SR * r)
    e[:ai] = np.linspace(0, 1, ai)
    if ri < n:
        e[-ri:] = np.linspace(1, 0, ri) ** 2
    return e

def note(f, dur, kind="sine", a=0.01, r=None, vol=1.0):
    x = t(dur)
    if kind == "sine":
        s = np.sin(2 * np.pi * f * x)
    elif kind == "bell":  # inharmonic partials with exponential decay
        s = sum(w * np.sin(2 * np.pi * f * m * x) * np.exp(-x * d)
                for m, w, d in [(1, 1, 2.5), (2.76, .5, 4), (5.4, .25, 6), (8.9, .12, 9)])
    elif kind == "marimba":
        s = (np.sin(2 * np.pi * f * x) + .3 * np.sin(2 * np.pi * f * 4 * x) * np.exp(-x * 30)) * np.exp(-x * 5)
    elif kind == "square":
        s = np.sign(np.sin(2 * np.pi * f * x)) * .6 + .4 * np.sin(2 * np.pi * f * x)
    elif kind == "saw":
        s = 2 * (x * f - np.floor(.5 + x * f))
    elif kind == "pad":
        s = sum(np.sin(2 * np.pi * f * k * x + k) / k for k in (1, 2, 3)) * .6
    return s * env(len(x), a, r if r is not None else dur * .5) * vol

def seq(events, total):
    buf = np.zeros(int(SR * total) + SR)
    for start, s in events:
        i = int(SR * start)
        buf[i:i + len(s)] += s[: len(buf) - i]
    return buf[: int(SR * total)]

def reverb(x, mix=.25):
    y = x.copy()
    for d, g in [(.031, .5), (.047, .4), (.071, .3), (.113, .22), (.173, .15)]:
        k = int(SR * d)
        y[k:] += x[:-k] * g * mix * 2
    return y

def hz(n):  # MIDI note -> Hz
    return 440 * 2 ** ((n - 69) / 12)

def write(name, x, peak=.89):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        with wave.open(tmp.name, "w") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes((x * 32767).astype("<i2").tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", tmp.name, "-c:a", "libvorbis",
                        "-q:a", "4", os.path.join(out_dir, name + ".ogg")], check=True)
        os.unlink(tmp.name)
    print(name)

# --- Gentle -----------------------------------------------------------------------------------
# Dawn: slow major-seventh arpeggio over a soft pad.
ev = []
for bar in range(4):
    root = [60, 57, 53, 55][bar]
    ev.append((bar * 4, note(hz(root - 12), 4.2, "pad", a=1, r=1.5, vol=.35)))
    for i, iv in enumerate([0, 4, 7, 11, 12, 11, 7, 4]):
        ev.append((bar * 4 + i * .5, note(hz(root + iv), 1.6, "sine", a=.02, r=1.2, vol=.5)))
write("tune_dawn", reverb(seq(ev, 16), .4))

# Meadow: marimba melody in pentatonic.
mel = [72, 74, 76, 79, 81, 79, 76, 74, 72, 69, 72, 74, 76, 74, 72, 67]
ev = [(i * .375, note(hz(n), .9, "marimba")) for i, n in enumerate(mel * 2)]
ev += [(i * 1.5, note(hz(n), 1.4, "marimba", vol=.6)) for i, n in enumerate([48, 55, 52, 55] * 2)]
write("tune_meadow", reverb(seq(ev, 12), .3))

# Lumen: crystalline bell chimes.
ev = [(i * .8, note(hz(n), 2.5, "bell", a=.002, r=1.5, vol=.7))
      for i, n in enumerate([84, 79, 76, 81, 84, 88, 86, 79, 76, 79, 81, 83, 84, 79, 72, 76])]
write("tune_lumen", reverb(seq(ev, 13.6), .45))

# --- Medium -----------------------------------------------------------------------------------
# Tidal: swelling chords that build.
ev = []
for i, ch in enumerate([[57, 60, 64], [53, 57, 60, 65], [55, 59, 62, 67], [57, 60, 64, 69]]):
    for n in ch:
        ev.append((i * 3, note(hz(n), 3.4, "pad", a=1.2, r=1, vol=.4)))
    ev.append((i * 3 + 1.5, note(hz(ch[-1] + 12), 1.5, "bell", a=.005, r=1, vol=.4)))
write("tune_tidal", reverb(seq(ev, 12), .4))

# Pulse: classic digital alarm beeps, four-beat groups.
ev = []
for g in range(8):
    for b in range(4):
        ev.append((g * 1.25 + b * .14, note(1760, .09, "sine", a=.003, r=.02)))
write("tune_pulse", seq(ev, 10))

# --- Harsh ------------------------------------------------------------------------------------
# Ascent: rising square-wave arpeggio, faster each round.
ev, tpos = [], 0.0
for rnd in range(6):
    step = .16 - rnd * .015
    for n in [60, 64, 67, 72, 76, 79, 84]:
        ev.append((tpos, note(hz(n + rnd), step * 1.3, "square", a=.003, r=.03, vol=.6)))
        tpos += step
    tpos += .2
write("tune_ascent", seq(ev, tpos))

# Klaxon: harsh two-tone horn.
ev = []
for i in range(10):
    ev.append((i * .9, note(440, .4, "saw", a=.005, r=.05) + note(554, .4, "saw", a=.005, r=.05)))
    ev.append((i * .9 + .45, note(370, .4, "saw", a=.005, r=.05) + note(466, .4, "saw", a=.005, r=.05)))
write("tune_klaxon", seq(ev, 9))

# Siren: fast frequency sweep with grit.
x = t(8)
f = 900 + 500 * np.sign(np.sin(2 * np.pi * .9 * x)) * 0 + 500 * np.sin(2 * np.pi * 1.6 * x)
phase = 2 * np.pi * np.cumsum(f) / SR
write("tune_siren", np.sign(np.sin(phase)) * .5 + np.sin(phase * 2) * .3)

# --- Board sounds ------------------------------------------------------------------------------
def click(f, dur, noise=.3, decay=60):
    x = t(dur)
    rng = np.random.default_rng(1)
    s = (np.sin(2 * np.pi * f * x) + noise * rng.standard_normal(len(x))) * np.exp(-x * decay)
    return s

write("sfx_move", click(320, .12, .5, 55), .6)
write("sfx_capture", seq([(0, click(260, .16, .8, 40)), (.03, click(520, .1, .4, 70))], .2), .75)
write("sfx_solved", reverb(seq([(i * .09, note(hz(n), .9, "bell", a=.002, r=.6, vol=.6))
                                for i, n in enumerate([72, 76, 79, 84, 88])], 1.6), .4), .7)
