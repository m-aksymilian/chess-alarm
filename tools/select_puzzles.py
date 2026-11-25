"""Pick 1000 short Lichess puzzles (CC0) for the bundled asset.

Usage: python3 tools/select_puzzles.py lichess_db_puzzle.csv app/src/main/assets/puzzles.json
"Short" = at most 2 moves for the solver (Lichess Moves field <= 4 plies incl. the setup move).
"""
import csv, json, random, sys

src, dst = sys.argv[1], sys.argv[2]
random.seed(42)
# Generic tags that never make a useful display theme.
GENERIC = {"short", "oneMove", "middlegame", "endgame", "opening", "advantage", "crushing",
           "equality", "master", "masterVsMaster", "superGM", "long", "veryLong", "kingsideAttack",
           "queensideAttack", "mate"}
buckets = {"mateIn1": [], "mateIn2": [], "tactic": []}
with open(src, newline="") as f:
    for r in csv.DictReader(f):
        moves = r["Moves"].split()
        if len(moves) not in (2, 4):
            continue
        rating, rd = int(r["Rating"]), int(r["RatingDeviation"])
        if not (1200 <= rating <= 1900) or rd > 80:
            continue
        if int(r["Popularity"]) < 90 or int(r["NbPlays"]) < 1000:
            continue
        themes = r["Themes"].split()
        if "mateIn1" in themes:
            kind = "mateIn1"
        elif "mateIn2" in themes:
            kind = "mateIn2"
        elif "mate" in themes:
            continue
        else:
            kind = "tactic"
        specific = [t for t in themes if t not in GENERIC]
        theme = kind if kind != "tactic" else (specific[0] if specific else "tactic")
        buckets[kind].append({
            "id": r["PuzzleId"], "fen": r["FEN"], "moves": moves, "rating": rating,
            "theme": theme, "themes": themes,
        })

targets = {"mateIn1": 300, "mateIn2": 300, "tactic": 400}
chosen = []
for kind, n in targets.items():
    pool = buckets[kind]
    # Stratify by 100-point rating bands so every difficulty range is well covered.
    bands = {}
    for p in pool:
        bands.setdefault(min(p["rating"] // 100, 18), []).append(p)
    per = n // len(bands)
    picked = []
    for b in sorted(bands):
        random.shuffle(bands[b])
        picked += bands[b][:per]
    rest = [p for p in pool if p not in picked]
    random.shuffle(rest)
    picked += rest[: n - len(picked)]
    chosen += picked
    print(kind, len(pool), "->", len(picked), file=sys.stderr)

chosen.sort(key=lambda p: p["rating"])
with open(dst, "w") as f:
    json.dump({"source": "Lichess puzzle database (https://database.lichess.org), CC0",
               "puzzles": chosen}, f, separators=(",", ":"))
print(len(chosen), "puzzles written", file=sys.stderr)
