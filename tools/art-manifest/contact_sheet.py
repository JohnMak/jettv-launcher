#!/usr/bin/env python3
"""Step 2: render the owner contact sheet for the vetted painting set.

BUILD-TIME ONLY. Reads vetted.json (Step 1 output) + art-manifest.json, and the
locally downloaded source snapshots in sources/ (Step 2's one-time download — these
exact bytes are what Step 3 later publishes, no re-download after approval). Emits a
single self-contained HTML file: a primary gallery of the vetted (kept) set grouped by
preset, each card showing a thumbnail + id + corrected title/year/artist + preset
tags, PLUS a compact secondary gallery of every dropped entry with its id/reason so
the owner can catch a misjudged drop and feed a correction back into vetted.json
(curate.py) before Step 3 ever runs.

Thumbnails are small in-memory re-encodes of the downloaded source (long edge capped,
JPEG) embedded as base64 data: URIs — the page is one file, safe to send whole.
Rendering itself touches no network; no inline style="..." attributes (dark-theme
safe); a <style> block in <head> carries all presentation.

`--fetch` performs the ONE-TIME dev-machine download of sources/ (direct HTTPS to
Wikimedia — not a .ru host, no RU-proxy) for every vetted (kept) entry not already
present on disk, before rendering. Re-run is idempotent: an existing non-empty file is
left untouched (Step 2's "exact bytes reviewed == exact bytes later published"
guarantee — no silent re-download after the owner has looked at a snapshot). The L1
approval hash (curate.compute_snapshot_hash) only ever reads bytes for the KEPT ids,
so sources/ may also hold cached bytes for reviewed-but-dropped entries (harmless
leftovers from the Step 1 visual suitability pass; used only to illustrate the
optional "Dropped" gallery below — never hashed, never published).
"""
import argparse, base64, io, json, os, sys, time, urllib.request
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import curate  # noqa: E402


class ContactSheetError(RuntimeError):
    """Fatal generation error: the vetted set does not match the live sources, or a
    kept painting has no decodable preview. The contact sheet is the owner's visual
    gate — it MUST NOT render a page that silently omits previews while still showing
    the stored approval hash."""

FETCH_USER_AGENT = ("jettv-launcher-art-curation/1.0 "
                     "(https://jmwork.pro; makeev.john@gmail.com) owner contact-sheet review")
THUMB_MAX_EDGE = 480
THUMB_QUALITY = 72


def _find_source(sources_dir, entry_id):
    for ext in ("jpg", "jpeg", "png"):
        p = os.path.join(sources_dir, "%s.%s" % (entry_id, ext))
        if os.path.exists(p):
            return p
    return None


def fetch_sources(manifest_entries, wanted_ids, sources_dir, delay_s=0.3):
    """One-time dev-machine download (direct HTTPS, no RU-proxy — Wikimedia is not a
    .ru host) of the manifest's already format/mime-gated thumbnail URL for every
    wanted id not already on disk. Returns (ok_count, fail_count); never raises on a
    single-file failure (reported to stderr, loop continues)."""
    os.makedirs(sources_dir, exist_ok=True)
    by_id = {e["id"]: e for e in manifest_entries}
    ok, fail = 0, 0
    for eid in wanted_ids:
        if _find_source(sources_dir, eid):
            ok += 1
            continue
        e = by_id.get(eid)
        if e is None:
            fail += 1
            sys.stderr.write("FETCH FAIL id=%s: not present in manifest\n" % eid)
            continue
        ext = "png" if e.get("thumb_mime") == "image/png" else "jpg"
        dest = os.path.join(sources_dir, "%s.%s" % (eid, ext))
        tmp = dest + ".part"
        req = urllib.request.Request(e["url"], headers={"User-Agent": FETCH_USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
                f.write(r.read())
            os.replace(tmp, dest)
            ok += 1
            sys.stderr.write("FETCH OK   %s (%d bytes)\n" % (dest, os.path.getsize(dest)))
        except Exception as ex:
            fail += 1
            sys.stderr.write("FETCH FAIL id=%s: %s\n" % (eid, ex))
            if os.path.exists(tmp):
                os.remove(tmp)
        time.sleep(delay_s)
    return ok, fail


def make_thumb_data_uri(path, max_edge=THUMB_MAX_EDGE, quality=THUMB_QUALITY):
    """Pure-ish (filesystem read only): downsize to max_edge long side, re-encode JPEG,
    return a `data:image/jpeg;base64,...` URI string. None if PIL unavailable or the
    file can't be decoded (caller falls back to a text-only card)."""
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        with Image.open(path) as im:
            im = im.convert("RGB")
            w, h = im.size
            scale = min(1.0, max_edge / float(max(w, h)))
            if scale < 1.0:
                im = im.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, format="JPEG", quality=quality, optimize=True)
            b64 = base64.b64encode(buf.getvalue()).decode("ascii")
            return "data:image/jpeg;base64,%s" % b64
    except Exception:
        return None


def _esc(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


PRESET_TAG_LABELS = OrderedDict([
    ("era:19c", "XIX век"),
    ("movement:peredvizhniki", "Передвижники"),
    ("movement:avantgarde", "Авангард"),
    ("era:empire", "era:empire"),
    ("era:20c", "era:20c"),
    ("artist:surikov", "artist:surikov"),
])


def _tag_chips(categories):
    chips = []
    for c in categories:
        label = PRESET_TAG_LABELS.get(c, c)
        chips.append('<span class="chip">%s</span>' % _esc(label))
    return "".join(chips)


def render_kept_card(entry, data_uri):
    img_html = (
        '<img class="thumb" src="%s" alt="%s" loading="lazy">' % (data_uri, _esc(entry["title"]))
        if data_uri else '<div class="thumb thumb-missing">нет превью</div>'
    )
    return (
        '<figure class="card card-kept">'
        + img_html
        + '<figcaption>'
        + '<div class="cap-title">%s</div>' % _esc(entry["title"])
        + '<div class="cap-meta">%s &middot; %s</div>' % (_esc(entry["artist"] or "—"), _esc(entry["year"] or "—"))
        + '<div class="cap-id">id %s</div>' % _esc(entry["id"])
        + '<div class="chips">%s</div>' % _tag_chips(entry["categories"])
        + '</figcaption></figure>'
    )


def render_dropped_card(d, data_uri):
    img_html = (
        '<img class="thumb thumb-small" src="%s" alt="" loading="lazy">' % data_uri
        if data_uri else '<div class="thumb thumb-small thumb-missing">нет превью</div>'
    )
    return (
        '<figure class="card card-dropped">'
        + img_html
        + '<figcaption>'
        + '<div class="cap-title">%s</div>' % _esc(d["title"])
        + '<div class="cap-id">id %s</div>' % _esc(d["id"])
        + '<div class="chips"><span class="chip chip-reason">%s</span></div>' % _esc(d["reason"])
        + '</figcaption></figure>'
    )


def render_recount_table(recount):
    rows = []
    for key, r in recount.items():
        status_class = "status-offered" if r["status"] == "OFFERED" else "status-hidden"
        rows.append(
            '<tr class="%s"><td>%s</td><td>%d</td><td>%d</td><td>%s</td></tr>' % (
                status_class, _esc(r["label"]), r["count"], r["min"], _esc(r["status"]))
        )
    return (
        '<table class="recount"><thead><tr>'
        '<th>Пресет</th><th>Кол-во</th><th>Минимум</th><th>Статус</th>'
        '</tr></thead><tbody>' + "".join(rows) + '</tbody></table>'
    )


def render_html(vetted, sources_dir):
    """Render the owner contact sheet. FAILS CLOSED (raises ContactSheetError) when:
      1. the live sources in sources_dir do not reproduce vetted's stored approval
         hash (the page would otherwise advertise a snapshot it did not render), or
      2. any kept painting lacks a decodable preview (missing file, Pillow
         unavailable, or decode failure) — the owner must see every vetted image, not
         a text-only placeholder masquerading as a reviewed work.
    Dropped entries may still degrade to a text-only card (illustrative only, never
    hashed, never published)."""
    kept = vetted["kept"]
    dropped = vetted["dropped"]
    recount = vetted["preset_recount"]

    # (1) every kept painting must have a decodable preview (missing source, Pillow
    # unavailable, or an undecodable image all count as a failure).
    kept_cards = []
    missing_previews = []
    for e in kept:
        p = _find_source(sources_dir, e["id"])
        data_uri = make_thumb_data_uri(p) if p else None
        if data_uri is None:
            missing_previews.append(e["id"])
            continue
        kept_cards.append(render_kept_card(e, data_uri))
    if missing_previews:
        raise ContactSheetError(
            "no decodable preview for %d kept painting(s): %s "
            "(missing source, Pillow unavailable, or undecodable image)"
            % (len(missing_previews), missing_previews))

    # (2) integrity: the bytes we just rendered must reproduce the stored approval
    # hash, or the page would advertise a snapshot it did not render.
    stored = vetted.get("approved_snapshot_sha256")
    live = curate.compute_snapshot_hash(kept, sources_dir)
    if not stored or live != stored:
        raise ContactSheetError(
            "snapshot mismatch: live sources hash %s != stored approved_snapshot_sha256 %s "
            "(sources/ out of sync with vetted.json — re-run curate.py / --fetch)"
            % (live, stored))

    dropped_cards = []
    for d in dropped:
        p = _find_source(sources_dir, d["id"])
        data_uri = make_thumb_data_uri(p) if p else None
        dropped_cards.append(render_dropped_card(d, data_uri))

    av = recount["AVANTGARDE"]
    av_verdict = (
        '<p class="callout callout-warn">«Авангард» HIDDEN: %d vetted работ из минимум %d '
        '(не хватает %d). Исходный пул (5 файлов в сыром манифесте) почти целиком оказался '
        'не живописью (манифесты, газетная статья, фото выставки) — см. раздел Dropped ниже.'
        '</p>' % (av["count"], av["min"], av["min"] - av["count"])
        if av["status"] == "HIDDEN" else
        '<p class="callout callout-ok">«Авангард» OFFERED: %d vetted работ.</p>' % av["count"]
    )

    html = """<!doctype html>
<meta charset="utf-8">
<title>ART P2 — Contact Sheet (owner review)</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 0; padding: 24px;
         background: #fff; color: #111; }
  @media (prefers-color-scheme: dark) {
    body { background: #14161a; color: #e8e8e8; }
  }
  h1 { font-size: 1.4rem; margin-bottom: 4px; }
  h2 { font-size: 1.1rem; margin-top: 2.2rem; border-bottom: 1px solid #8884; padding-bottom: 6px; }
  .subtitle { opacity: 0.7; margin-top: 0; }
  .callout { padding: 10px 14px; border-radius: 8px; margin: 12px 0; }
  .callout-warn { background: #6b3a0022; border: 1px solid #c77b1c; }
  .callout-ok { background: #0a6b3a22; border: 1px solid #1cc77b; }
  table.recount { border-collapse: collapse; margin: 10px 0 20px; }
  table.recount th, table.recount td { border: 1px solid #8886; padding: 6px 12px; text-align: left; }
  tr.status-hidden td { opacity: 0.75; }
  tr.status-hidden td:last-child { color: #c77b1c; font-weight: 600; }
  tr.status-offered td:last-child { color: #1a9a5c; font-weight: 600; }
  .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
  .gallery-dropped { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
  .card { margin: 0; border: 1px solid #8884; border-radius: 10px; overflow: hidden;
          background: #8881 0d; display: flex; flex-direction: column; }
  .card-dropped { opacity: 0.85; }
  .thumb { width: 100%; height: 160px; object-fit: cover; display: block; background: #8882; }
  .thumb-small { height: 110px; }
  .thumb-missing { display: flex; align-items: center; justify-content: center;
                    font-size: 0.75rem; opacity: 0.6; }
  figcaption { padding: 8px 10px; font-size: 0.82rem; }
  .cap-title { font-weight: 600; margin-bottom: 2px; }
  .cap-meta { opacity: 0.75; }
  .cap-id { opacity: 0.5; font-size: 0.72rem; margin-top: 2px; }
  .chips { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px; }
  .chip { font-size: 0.68rem; padding: 2px 7px; border-radius: 999px; background: #8884; }
  .chip-reason { background: #c77b1c33; }
  .code-block { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.78rem;
                 background: #8882; padding: 10px 12px; border-radius: 8px; overflow-x: auto;
                 white-space: pre-wrap; word-break: break-all; }
  .badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 0.78rem;
           background: #8884; margin-right: 6px; }
  .badge-pending { background: #c77b1c33; }
</style>
<h1>ART P2 &mdash; Contact Sheet</h1>
<p class="subtitle">Шаг 2 (owner visual review) &middot; """ + str(vetted["kept_count"]) + """ сохранено
из """ + str(vetted["kept_count"] + vetted["dropped_count"]) + """ &middot;
<span class="badge badge-pending">approved: false</span></p>

<h2>Пересчёт по пресетам</h2>
""" + render_recount_table(recount) + av_verdict + """

<h2>Vetted set (""" + str(len(kept)) + """)</h2>
<div class="gallery">
""" + "\n".join(kept_cards) + """
</div>

<h2>Dropped (""" + str(len(dropped)) + """) &mdash; просьба свериться, нет ли ошибочного отсева</h2>
<div class="gallery gallery-dropped">
""" + "\n".join(dropped_cards) + """
</div>

<h2>Snapshot hash (L1 approval binding)</h2>
<p>Утверждение владельца фиксируется этим хэшем в <code>vetted.json</code>
(&laquo;approved&raquo; станет true только после явного подтверждения;
любое изменение набора или байтов исходника меняет хэш):</p>
<div class="code-block">approved_snapshot_sha256: """ + _esc(vetted["approved_snapshot_sha256"]) + """</div>
"""
    return html


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--vetted", default=os.path.join(HERE, "vetted.json"))
    ap.add_argument("--manifest", default=os.path.join(HERE, "art-manifest.json"))
    ap.add_argument("--sources", default=os.path.join(HERE, "sources"))
    ap.add_argument("--out", default=os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(HERE))),
        "materials", "art-contact-sheet.html"))
    ap.add_argument("--fetch", action="store_true",
                     help="download missing source snapshots for the vetted (kept) "
                          "set before rendering (one-time; idempotent)")
    args = ap.parse_args()

    with open(args.vetted) as f:
        vetted = json.load(f)

    if args.fetch:
        with open(args.manifest) as f:
            manifest = json.load(f)
        wanted = [e["id"] for e in vetted["kept"]]
        ok, fail = fetch_sources(manifest["entries"], wanted, args.sources)
        sys.stderr.write("fetch: ok=%d fail=%d\n" % (ok, fail))
        if fail:
            sys.stderr.write("FATAL: %d source download(s) failed; re-run --fetch.\n" % fail)
            return 1

    try:
        html = render_html(vetted, args.sources)
    except ContactSheetError as ex:
        sys.stderr.write("FATAL: %s\n" % ex)
        return 1
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    tmp = args.out + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(html)
    os.replace(tmp, args.out)
    sys.stderr.write("contact sheet: %s (%d bytes)\n" % (args.out, os.path.getsize(args.out)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
