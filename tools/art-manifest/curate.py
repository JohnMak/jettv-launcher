#!/usr/bin/env python3
"""Step 1 curation: project the 50-entry PD manifest (art-manifest.json) down to a
vetted painting set, with per-entry caption corrections and an explicit per-preset
recount against MIN_PRESET_WORKS.

BUILD-TIME ONLY. Pure projection (no network). Reads art-manifest.json, applies the
human/contact-sheet curation decisions below (DROP_IDS + CORRECTIONS, captured from a
visual review of every kept entry's downloaded source image — Step 2's sources/), and
writes vetted.json. Run after sources/ has been populated (contact_sheet.py / the
developer's own review) so corrections can be checked against the actual images.

Curation decisions are PRE-OWNER-APPROVAL: the public-domain gate (generate.py) is not
a suitability gate (CLAUDE.md rule 5 — no free-text parsing drives the decision; every
drop reason below is a human visual judgment, recorded explicitly). The owner reviews
the contact sheet (Step 2) and can feed corrections back into this file; vetted.json's
"approved" stays false until the owner signs off (L1 binding, see compute_snapshot_hash).

Usage:
    python3 curate.py                  # art-manifest.json -> vetted.json + recount
    python3 curate.py --out FILE
"""
import argparse, hashlib, json, os, re, sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = 1
MIN_PRESET_WORKS = 4  # D6, owner-approved plan default

# ---- PRESETS (architecture D1/D6): key -> category tag filter, None = no filter ----
PRESETS = OrderedDict([
    ("ALL", None),
    ("PEREDVIZHNIKI", "movement:peredvizhniki"),
    ("ERA_19C", "era:19c"),
    ("AVANTGARDE", "movement:avantgarde"),
])
PRESET_LABELS = {
    "ALL": "Всё", "PEREDVIZHNIKI": "Передвижники",
    "ERA_19C": "XIX век", "AVANTGARDE": "Авангард",
}

# ---- non-painting drops (Point A, architect-identified by title/content alone) -----
# Four avant-garde text artifacts + one exhibition installation photo + one book DjVu
# thumbnail mis-captioned "2014" — none of these depict a painting.
NON_PAINTING_DROPS = {
    145201352: "non-painting:newspaper-article",
    145222274: "non-painting:manifesto",
    145221847: "non-painting:manifesto",
    154018212: "non-painting:exhibition-photo",
    145222807: "non-painting:press-clipping",
    34219161: "non-painting:book-djvu",
}

# ---- suitability drops (visual review of every kept entry's downloaded source in
# sources/, Step 2) — "journal title/text pages, and any image judged unsuitable"
# per the plan's Step 1. A page qualifies as suitable only when a single illustration
# dominates the frame with at most a short (1-3 line) credit header and a 1-line
# caption below; a page sharing substantial frame space with unrelated running text
# (literary serials, biographical articles), a full ornate journal masthead banner, a
# multi-topic photo composite, a per-item text catalog, or a plain photographic
# portrait of a person is NOT a usable painting reproduction (no cropping step exists
# downstream — Step 3 only downscales).
UNSUITABLE_DROPS = {
    14032078: "unsuitable:photo-portrait",           # photo of the painter, not a painting
    17971944: "unsuitable:poster-with-dense-text",    # political lubok poster + verse text
    56421854: "unsuitable:text-heavy-catalog-page",   # 10-panel index + per-item text blurbs
    56379214: "unsuitable:unrelated-multi-topic-composite",  # landscape + submarine photos
    56418721: "unsuitable:unrelated-multi-topic-composite",  # two unrelated war-illustration topics
    56378527: "unsuitable:writer-portrait-not-artwork",  # Kostomarov portrait + biography
    56378418: "unsuitable:writer-portrait-not-artwork",  # Sienkiewicz portrait + travelogue
    56382137: "unsuitable:non-art-text-dominates-frame",
    56382175: "unsuitable:non-art-text-dominates-frame",
    56421054: "unsuitable:non-art-text-dominates-frame",
    56388383: "unsuitable:non-art-text-dominates-frame",
    56387811: "unsuitable:non-art-text-dominates-frame",
    56418782: "unsuitable:non-art-text-dominates-frame",
    56379527: "unsuitable:non-art-text-dominates-frame",
    56420588: "unsuitable:non-art-text-dominates-frame",
    56420771: "unsuitable:non-art-text-dominates-frame",
    56418580: "unsuitable:non-art-text-dominates-frame",
    56418631: "unsuitable:non-art-text-dominates-frame",
    56378569: "unsuitable:non-art-text-dominates-frame",
    56378587: "unsuitable:non-art-text-dominates-frame",
    56418716: "unsuitable:non-art-text-dominates-frame",
    56378938: "unsuitable:non-art-text-dominates-frame",
    56388020: "unsuitable:non-art-text-dominates-frame",
    56382750: "unsuitable:non-art-text-dominates-frame",
    56382368: "unsuitable:non-art-text-dominates-frame",
}

DROPS = dict(NON_PAINTING_DROPS)
DROPS.update(UNSUITABLE_DROPS)

# ---- caption corrections for every KEPT entry (D4: corrected per-entry, never parsed
# from the raw boilerplate Artist/title/year fields). Transcribed from the printed
# caption/credit line visible on each entry's own source page (sources/<id>.*), not
# fabricated. `year` is the journal ISSUE year read off the page header (far more
# trustworthy than the manifest's boilerplate "1872" default — the journal masthead
# literally prints "Живописное обозрение <YYYY>"); a handful of entries already carried
# a genuine non-boilerplate caption and are passed through unchanged.
CORRECTIONS = {
    199396251: {"title": "Вид на Лисью гору", "artist": "Худояров (?)", "year": "1850"},
    143756705: {"title": "Конец охоты", "artist": "С. С. Ворошилов", "year": "1900"},
    65685196: {"title": "Summer Evening. Melancholy", "artist": "Борис Анисфельд", "year": "1910"},
    56420704: {"title": "Свадьба в Малороссии", "artist": "Н. К. Пимоненко", "year": "1891"},
    56420999: {"title": "На сенокосе", "artist": "В. Голынский", "year": "1891"},
    56422631: {"title": "Модный романс", "artist": "И. Панкевич", "year": "1891"},
    56383048: {"title": "Разлучница", "artist": "К. Трутовский", "year": "1891"},
    56365988: {"title": "Возвращение из школы", "artist": "Г. Ф. Рыбаков", "year": "1891"},
    56391910: {"title": "Первый снег", "artist": "Барщов", "year": "1891"},
    56421896: {"title": "Гаданье на воске", "artist": "В. Пимоненко", "year": "1891"},
    56381802: {"title": "Охота на волка", "artist": "Н. С. Самокиш", "year": "1891"},
    56421822: {"title": "Венчание в тюрьме", "artist": "Н. С. Матвеев", "year": "1890"},
    56419114: {"title": "Казаки на рекогносцировке", "artist": "П. Грузинский", "year": "1885"},
    56419159: {"title": "Парад Лейб-гвардии Московского полка", "artist": "А. Сафонов", "year": "1885"},
    56421603: {"title": "Тройка во время метели на Кавказе", "artist": "П. Грузинский", "year": "1886"},
    56420597: {"title": "Весеннее половодье", "artist": "проф. Ю. Ю. Клевер", "year": "1891"},
    56422795: {"title": "Против воли постриженная", "artist": "Н. Матвеев", "year": "1891"},
    56418866: {"title": "Времена года: Лето", "artist": "Н. Лоренц", "year": "1885"},
    56379830: {"title": "Виды г. Вятки и ближайших окрестностей", "artist": "Н. Хохряков", "year": "1886"},
}


def curate(manifest_entries, drops=None, corrections=None):
    """Pure projection. manifest_entries: list of raw art-manifest.json entry dicts.
    Returns (kept, dropped): kept = list of {id,title,artist,year,categories} in
    manifest order with corrections applied; dropped = list of {id,title,reason}.
    An entry whose id is not in `drops` AND not in `corrections` is kept with its
    original title/artist/year verbatim (defensive default: never silently drop).
    """
    drops = DROPS if drops is None else drops
    corrections = CORRECTIONS if corrections is None else corrections
    kept, dropped = [], []
    for e in manifest_entries:
        eid = e["id"]
        if eid in drops:
            dropped.append(OrderedDict([
                ("id", eid), ("title", e["title"]), ("reason", drops[eid]),
            ]))
            continue
        c = corrections.get(eid, {})
        kept.append(OrderedDict([
            ("id", eid),
            ("title", c.get("title", e["title"])),
            ("artist", c.get("artist", e.get("artist", ""))),
            ("year", c.get("year", e["year"])),
            ("categories", list(e["categories"])),
        ]))
    return kept, dropped


def preset_recount(kept, min_works=MIN_PRESET_WORKS):
    """Pure. Returns OrderedDict preset_key -> {label,count,min,status}.

    D6 nonempty-pool exception: the ALL preset («Всё», tag=None) is the user's only
    guaranteed fallback and is OFFERED whenever the vetted set is nonempty — the
    MIN_PRESET_WORKS floor applies ONLY to the tag-filtered category presets. The
    exception is expressed as an effective minimum of 1 for ALL, so the table
    invariant `status == OFFERED iff count >= min` holds uniformly."""
    out = OrderedDict()
    for key, tag in PRESETS.items():
        if tag is None:
            count = len(kept)
            eff_min = 1  # D6: ALL offered whenever nonempty (never the 4-work floor)
        else:
            count = sum(1 for e in kept if tag in e["categories"])
            eff_min = min_works
        out[key] = OrderedDict([
            ("label", PRESET_LABELS[key]),
            ("count", count),
            ("min", eff_min),
            ("status", "OFFERED" if count >= eff_min else "HIDDEN"),
        ])
    return out


def source_sha1_map(kept, sources_dir):
    """Read every kept entry's reviewed source file in sources_dir and return an
    OrderedDict {str(id): sha1hex or None} in ascending-id order. The None sentinel
    marks an id with no on-disk source (never fatal here). sources_dir is gitignored,
    so this reads bytes only where they exist locally; the committed digest map in
    vetted.json is what the offline approval check trusts."""
    out = OrderedDict()
    for e in sorted(kept, key=lambda x: x["id"]):
        eid = e["id"]
        path = None
        for ext in ("jpg", "jpeg", "png"):
            cand = os.path.join(sources_dir, "%s.%s" % (eid, ext))
            if os.path.exists(cand):
                path = cand
                break
        if path is None:
            out[str(eid)] = None
            continue
        h = hashlib.sha1()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        out[str(eid)] = h.hexdigest()
    return out


def snapshot_hash_from_digests(kept, source_sha1):
    """Pure L1 approval binding: sha256 over the canonical kept-entry list (sorted by
    id, stable key order, INCLUDING each entry's preset `categories`) AND the committed
    per-id source sha1 digests. Any change to a kept entry's id/title/artist/year/
    categories or to a source digest flips this hash. `source_sha1` is a mapping
    {str(id): sha1hex or None}; it is re-keyed to the kept ids in id order so input
    ordering is irrelevant."""
    entries_canon = [
        OrderedDict([("id", e["id"]), ("title", e["title"]),
                     ("artist", e["artist"]), ("year", e["year"]),
                     ("categories", list(e["categories"]))])
        for e in sorted(kept, key=lambda x: x["id"])
    ]
    source_hashes = OrderedDict()
    for e in entries_canon:
        source_hashes[str(e["id"])] = source_sha1.get(str(e["id"]))
    canonical = json.dumps(
        OrderedDict([("entries", entries_canon), ("source_sha1", source_hashes)]),
        sort_keys=False, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def compute_snapshot_hash(kept, sources_dir):
    """Convenience: read live source bytes from sources_dir and bind them. Equivalent
    to snapshot_hash_from_digests(kept, source_sha1_map(kept, sources_dir)) — one logic
    path, used at build time (sources present) and in local-only byte re-verification."""
    return snapshot_hash_from_digests(kept, source_sha1_map(kept, sources_dir))


def _atomic_write(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
    os.replace(tmp, path)


def build_vetted(manifest, sources_dir):
    kept, dropped = curate(manifest["entries"])
    recount = preset_recount(kept)
    source_sha1 = source_sha1_map(kept, sources_dir)
    snapshot_sha256 = snapshot_hash_from_digests(kept, source_sha1)
    vetted = OrderedDict([
        ("schema", SCHEMA),
        ("source_manifest_generated_at", manifest.get("generated_at")),
        ("min_preset_works", MIN_PRESET_WORKS),
        ("approved", False),
        ("approved_snapshot_sha256", snapshot_sha256),
        ("kept_count", len(kept)),
        ("dropped_count", len(dropped)),
        ("preset_recount", recount),
        # committed per-id source digests: lets the offline approval check verify the
        # hash on a clean checkout where sources/ (gitignored) is absent. Live byte
        # re-verification against these digests is a local-only test.
        ("source_sha1", source_sha1),
        ("kept", kept),
        ("dropped", dropped),
    ])
    return vetted


def print_recount(recount):
    sys.stderr.write("\n=== PER-PRESET RECOUNT (vetted set) ===\n")
    for key, r in recount.items():
        sys.stderr.write("  %-14s %-14s count=%-3d min=%-3d %s\n" % (
            key, r["label"], r["count"], r["min"], r["status"]))
    av = recount["AVANTGARDE"]
    if av["status"] == "HIDDEN":
        shortfall = av["min"] - av["count"]
        sys.stderr.write(
            "\n«Авангард» HIDDEN: %d vetted work(s), needs >= %d (short by %d).\n"
            % (av["count"], av["min"], shortfall))
    for key, r in recount.items():
        if key != "AVANTGARDE" and r["status"] == "HIDDEN":
            shortfall = r["min"] - r["count"]
            sys.stderr.write(
                "«%s» HIDDEN: %d vetted work(s), needs >= %d (short by %d).\n"
                % (r["label"], r["count"], r["min"], shortfall))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default=os.path.join(HERE, "art-manifest.json"))
    ap.add_argument("--out", default=os.path.join(HERE, "vetted.json"))
    ap.add_argument("--sources", default=os.path.join(HERE, "sources"))
    args = ap.parse_args()

    with open(args.manifest) as f:
        manifest = json.load(f)

    manifest_ids = {e["id"] for e in manifest["entries"]}
    unknown = sorted((set(DROPS) | set(CORRECTIONS)) - manifest_ids)
    if unknown:
        sys.stderr.write(
            "FATAL: curation references id(s) not in %s: %s\n" % (args.manifest, unknown))
        return 1

    vetted = build_vetted(manifest, args.sources)
    _atomic_write(args.out, vetted)

    sys.stderr.write("kept=%d dropped=%d (of %d total)\n" % (
        vetted["kept_count"], vetted["dropped_count"],
        vetted["kept_count"] + vetted["dropped_count"]))
    print_recount(vetted["preset_recount"])
    sys.stderr.write("\napproved_snapshot_sha256 (candidate, approved=false): %s\n"
                      % vetted["approved_snapshot_sha256"])
    sys.stderr.write("vetted.json: %s\n" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
