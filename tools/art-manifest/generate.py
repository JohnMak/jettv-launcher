#!/usr/bin/env python3
"""Build-time art-manifest generator for the JetTV launcher ART screensaver.

BUILD-TIME ONLY. Queries Wikimedia Commons over direct HTTPS from the developer
machine, applies a fail-closed dual-jurisdiction public-domain gate, a width gate,
and a thumbnail-format gate, and emits a reviewed-and-committed art-manifest.json.

The TV app NEVER runs this and NEVER calls the Commons API — it reads the frozen
manifest bundled as an asset. No runtime image-fetch path lives in app source.

Usage:
    python3 generate.py                 # regenerate against live Commons -> art-manifest.json
    python3 generate.py --out FILE      # write manifest elsewhere
    python3 generate.py --no-evidence   # skip per-file evidence snapshots

Gate (fail-closed at every step; unknown/ambiguous => REJECT):
  1. icon exclusion   - any source category matching an excluded substring => reject (P1: icons deferred to P3)
  2. width gate       - imageinfo.width >= config.width_min
  3. format gate      - thumburl present AND thumbmime in {image/jpeg,image/png} AND sane thumb dims
  4. dual-jurisdiction - >=1 accepted source-country template AND >=1 accepted US template
  4b. excluded-license veto - any co-occurring non-free/restrictive (CC/GFDL/OGL/...) template rejects
  5. per-file eligibility - self-contained source template admits; a life-based-only source is
     rejected UNCONDITIONALLY unless the pageid is in curation `life_based_overrides` (empty in P1).
     The decision NEVER parses the free-text Artist field.
Decision keys ONLY on transcluded license templates (prop=templates), never on
extmetadata.License/Categories (best-guess, non-authoritative).
"""
import argparse, html, json, os, re, sys, time, urllib.parse, urllib.request
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://commons.wikimedia.org/w/api.php"
SCHEMA = 2

# ---- accepted license templates (plan PD policy + CEO-approved B correction) ----
# Source-country (Russia / Empire / USSR) — one of these required.
SOURCE_TEMPLATES = {
    "PD-old-70", "PD-old-100", "PD-old-auto",
    "PD-RusEmpire", "PD-Russia-1996", "PD-Russia-expired", "PD-Russia",
}
# United States — one of these required IN ADDITION. Literal set per plan.
US_LITERAL = {"PD-1996", "PD-US-expired", "PD-1923", "PD-US"}
# CEO-approved B correction (2026-10-24): composite/auto templates that are the
# realized form of the US-expired assertion on Commons (literal set is almost
# never placed directly). These ARE genuine "PD in the US" statements, not a
# loosening — each asserts the underlying work's US copyright has expired.
US_COMPOSITE = {"PD-US-expired-text", "PD-old-X-expired", "PD-old-auto-expired"}
US_TEMPLATES = US_LITERAL | US_COMPOSITE

# PD-Art is a reproduction-rights layer only — NEVER a US (or source) basis.
# It is deliberately absent from both sets so {PD-old-70, PD-Art} with no real
# US template is rejected, and bare PD-Art is rejected.

# ---- B1: positive-exclusion veto (fail-closed) --------------------------------
# A valid source+US PD pair is NOT sufficient if the file ALSO carries a non-free /
# restrictive license template: a co-occurring CC-BY/CC-BY-SA/GFDL/OGL/PD-self/FAL/
# Licensed-PD-Art imposes attribution/share-alike/self-dedication obligations we will
# not carry on a screensaver, OR signals the uploader's own basis is not the claimed
# PD-by-age one. Per the plan PD policy ("Rejected (fail-closed): all CC-BY / CC-BY-SA
# ...; PD-self; ... any file with missing/ambiguous template data"), ANY such template
# on the file vetoes acceptance outright, regardless of the PD pair.
#
# Fail-closed posture (over-blocks before under-blocks), two mechanisms:
#   1. Known restrictive families — explicit exact names + anchored prefixes on the
#      normalized bare name (never a bare-substring scan, which would false-positive on
#      benign i18n templates like `GetFallback`).
#   2. Creative Commons catch-ALL — ANY `cc-*` / `cc0*` template that is not the benign
#      `Cc-pd-mark*` footer is treated as an unresolved/restrictive license and vetoed,
#      so an unknown or future CC variant name (e.g. a version we never enumerated)
#      cannot slip through. This is the "ambiguity => reject" rule for the dominant
#      non-free family on Commons.
# The PD-* public-domain family is intentionally benign: a PD-by-age/-publication
# assertion imposes NO reuse obligation and cannot be a hidden restriction; a bogus PD-*
# variant simply fails the positive source/US requirement. `Cc-pd-mark*` / `CC-PD-Mark`
# is a benign footer mark (not a basis, not a restriction). We deliberately do NOT run a
# generic "any license-looking name => reject" scan: Commons applies maintenance markers
# like `License template tag` / `License migration` to every file, so such a scan would
# over-block all files. Documented residual gap: a restrictive license from OUTSIDE the
# CC family under a novel, unlisted name could slip the veto — it is still gated by the
# dual positive source+US requirement (it can only reach the veto if it ALSO carries a
# genuine accepted source+US PD pair, which is already strong evidence).
EXCLUDED_LICENSE_EXACT = {
    "pd-self", "self", "fal",
    "copyright", "copyrighted",
    "gpl", "lgpl", "lppl", "agpl",
}
_EXCLUDED_LICENSE_PREFIXES = (
    "cc-by", "cc-sa", "cc-nc", "cc-nd",          # Creative Commons restrictive family (all versions)
    "gfdl",                                        # GNU Free Documentation License (+ its versioned forms)
    "ogl",                                         # UK Open Government Licence (OGL / OGL3) — attribution duty
    "licensed-pd-art",                             # PD-Art reproduction under a CC-licensed photo (+ -two/layout)
    "copyrighted-free-use", "copyright-",
    "attribution-", "noncommercial", "nonfree", "non-free", "noderiv",
    "fal-", "free-art-license", "freeart",
)


def _norm_tpl(name):
    """Lowercase + collapse separators (space/underscore -> hyphen) for stable matching."""
    return re.sub(r"[ _]+", "-", name.strip().lower())


def _is_restrictive_license(norm_name):
    n = norm_name
    if n in EXCLUDED_LICENSE_EXACT or any(n.startswith(p) for p in _EXCLUDED_LICENSE_PREFIXES):
        return True
    # Creative Commons catch-all: any cc-*/cc0* that is NOT the benign PD mark footer.
    if (n.startswith("cc-") or n.startswith("cc0")) and not n.startswith("cc-pd-mark"):
        return True
    return False


def excluded_license_hits(tpl):
    """Return sorted restrictive/non-free (or unresolved CC) license templates present
    in the file's template set. Non-empty => fail-closed veto (B1). PD-* assertions and
    the Cc-pd-mark footer are benign and never returned here."""
    return sorted(t for t in tpl if _is_restrictive_license(_norm_tpl(t)))

# ---- B3: per-file eligibility — source-basis classification (Option A) --------
# The eligibility DECISION never parses the free-text Commons `Artist` field (that is
# unbounded, metadata-polluted, hostile-ish text — a parser over it never converges and
# is the wrong place for a security decision; see CLAUDE.md rule 5). A file is admitted
# on exactly two deterministic, fail-closed grounds:
#   * SELF-CONTAINED source template — publication / URAA / categorical age≥100 bases
#     whose PD status is AUTHOR-INDEPENDENT (no per-author death-year to fetch/verify):
#       PD-RusEmpire   : published in the Russian Empire before 1917-11-07
#       PD-Russia-1996 : PD in Russia on the URAA date 1996-01-01
#       PD-old-100     : categorical "author died >100 years ago" (-> died before ~1926,
#                        life+70 expired before the 1996 URAA date -> URAA-clean).
#   * LIFE-BASED source template (PD-old-70 / PD-old-auto / PD-Russia-expired /
#     PD-Russia) — death-year dependent. Its PD status CANNOT be established from
#     templates alone, so in P1 a life-based-only file is REJECTED UNCONDITIONALLY. The
#     ONLY way such a file is ever admitted is an explicit per-pageid entry in curation's
#     `life_based_overrides` (a human who recorded the death facts for that exact file).
#     P1 ships that allowlist EMPTY; P3 populates the curated Soviet/life-based garnish.
SELF_CONTAINED_SOURCE = {"PD-RusEmpire", "PD-Russia-1996", "PD-old-100"}

TERM_RULE = {
    "PD-old-100": "author-death+100",
    "PD-old-70": "author-death+70",
    "PD-old-auto": "author-death+70 (auto)",
    "PD-RusEmpire": "published in the Russian Empire before 1917-11-07",
    "PD-Russia-1996": "PD in Russia on the URAA date 1996-01-01",
    "PD-Russia-expired": "Russian term expired (life+70)",
    "PD-Russia": "Russian term expired (life+70)",
}

_TAG_RE = re.compile(r"<[^>]+>")
_YEAR_RE = re.compile(r"(1[0-9]{3}|20[0-2][0-9])")
_WS_RE = re.compile(r"\s+")


def strip_html(v):
    """Strip HTML, unescape entities, drop Wikibase `label QS:` caption tails,
    collapse whitespace. (Commons ObjectName/Artist carry hidden multilingual
    `label QS:L..,"..."` divs whose text would otherwise concatenate into the value.)"""
    if not v:
        return ""
    s = html.unescape(_TAG_RE.sub(" ", v))
    s = s.split("label QS:")[0]
    return _WS_RE.sub(" ", s).strip()


def clean_url(u):
    """Drop incidental `utm_*` analytics params the imageinfo API appends to
    thumbnail URLs (verified: image bytes identical with/without). Resource
    identity is preserved; the committed manifest stays stable across campaigns."""
    if not u:
        return u
    parts = urllib.parse.urlsplit(u)
    if not parts.query:
        return u
    kept = [(k, v) for k, v in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
            if not k.lower().startswith("utm_")]
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path,
                                    urllib.parse.urlencode(kept), parts.fragment))


def extract_year(extmeta):
    for field in ("DateTimeOriginal", "DateTime", "date"):
        raw = (extmeta.get(field) or {}).get("value")
        if raw:
            m = _YEAR_RE.search(strip_html(raw))
            if m:
                return m.group(1)
    return ""


def norm_templates(rec):
    """Return the set of bare template names (Template: prefix stripped)."""
    out = set()
    for t in rec.get("templates", []):
        name = t["title"] if isinstance(t, dict) else t
        out.add(name.replace("Template:", "", 1))
    return out


def evaluate(rec, cfg):
    """PURE gate. No network. Returns dict(verdict, reason, entry).

    rec = {
      "pageid": int, "title": str, "lastrevid": int,
      "templates": [ {"title": "Template:PD-old-100"} , ... ]  (or bare strings),
      "imageinfo": { width,height,mime,thumbmime,thumburl,thumbwidth,thumbheight,url,timestamp,sha1,extmetadata },
      "source_categories": [ "Category:..." , ... ],
      "keys": [ "artist:shishkin", ... ]
    }
    """
    excl = [s.lower() for s in cfg.get("exclude_category_substrings", [])]
    width_min = cfg["config"]["width_min"]
    overrides = cfg.get("overrides", {})

    ii = rec.get("imageinfo") or {}
    cats = rec.get("source_categories", []) or []

    # 1. icon / excluded-category exclusion (fail-closed: any tainted source category)
    for c in cats:
        cl = c.lower()
        for s in excl:
            if s in cl:
                return {"verdict": "reject", "reason": "excluded-category:%s" % c, "entry": None}

    if not ii:
        return {"verdict": "reject", "reason": "no-imageinfo", "entry": None}

    # 2. width gate (original width)
    width = ii.get("width") or 0
    height = ii.get("height") or 0
    if width < width_min:
        return {"verdict": "reject", "reason": "width<%d (%s)" % (width_min, width), "entry": None}

    # 3. format gate — validate the thumbnail we will actually serve
    thumbmime = ii.get("thumbmime")
    thumburl = ii.get("thumburl")
    tw, th = ii.get("thumbwidth"), ii.get("thumbheight")
    if not thumburl:
        return {"verdict": "reject", "reason": "no-thumburl", "entry": None}
    if thumbmime not in ("image/jpeg", "image/png"):
        return {"verdict": "reject", "reason": "thumbmime-not-raster:%s" % thumbmime, "entry": None}
    if not tw or not th or tw < 256 or th < 256:
        return {"verdict": "reject", "reason": "thumb-dims-insane:%sx%s" % (tw, th), "entry": None}

    # 4. dual-jurisdiction PD gate — decided ONLY on transcluded templates
    tpl = norm_templates(rec)
    # 4a. conflict guard (fail-closed): a `Not-PD-*` template is Commons' own
    # negative assertion that a PD basis does NOT hold (e.g. Not-PD-US-expired-min-year
    # = US copyright NOT confirmed expired, likely URAA-restored). It overrides any
    # structurally-transcluded PD-US-expired-text, so presence of ANY such template
    # is a mixed/conflicting-license case -> reject (plan PD policy).
    conflict = sorted(t for t in tpl if t.lower().startswith("not-pd"))
    if conflict:
        return {"verdict": "reject", "reason": "conflicting-license:%s" % conflict[0], "entry": None}
    src_hits = sorted(tpl & SOURCE_TEMPLATES)
    us_hits = sorted(tpl & US_TEMPLATES)
    if not src_hits:
        return {"verdict": "reject", "reason": "no-accepted-source-template", "entry": None}
    if not us_hits:
        return {"verdict": "reject", "reason": "no-accepted-us-template", "entry": None}

    # 4b. positive-exclusion veto (B1, fail-closed): a valid source+US pair does NOT
    # rescue a file that also carries a non-free / restrictive license template. ANY
    # such co-occurring template vetoes acceptance outright.
    excl_hits = excluded_license_hits(tpl)
    if excl_hits:
        return {"verdict": "reject",
                "reason": "excluded-license:%s" % excl_hits[0], "entry": None}

    # 5. per-file eligibility (B3 / Option A, fail-closed) — the DECISION never parses
    # the free-text Artist field. A file is admitted ONLY on a self-contained source
    # template, OR (life-based-only) an explicit per-pageid curation override recording
    # the death facts. In P1 `life_based_overrides` is empty => every life-based-only
    # file is rejected unconditionally.
    extmeta = ii.get("extmetadata", {}) or {}
    self_contained = sorted(tpl & SELF_CONTAINED_SOURCE)
    lb_override = (cfg.get("life_based_overrides") or {}).get(str(rec["pageid"])) or {}
    lb_admitted = lb_override.get("eligible") is True
    if not self_contained and not lb_admitted:
        return {"verdict": "reject",
                "reason": "life-based-requires-pageid-curation:%s" % src_hits[0],
                "entry": None}

    # ---- accepted: build entry ----
    source_tpl = self_contained[0] if self_contained else src_hits[0]
    us_tpl = us_hits[0]
    term = TERM_RULE.get(source_tpl, "term asserted by %s" % source_tpl)
    if self_contained:
        notes = "self-contained PD basis (%s); author-independent, no death facts required" % source_tpl
    else:
        notes = lb_override.get("notes") or ("curated life-based override for pageid %s" % rec["pageid"])

    ov = overrides.get(str(rec["pageid"]), {})
    title = ov.get("title") or strip_html((extmeta.get("ObjectName") or {}).get("value", "")) \
        or rec.get("title", "").replace("File:", "").rsplit(".", 1)[0]
    artist_out = ov.get("artist") or strip_html((extmeta.get("Artist") or {}).get("value", ""))
    year = ov.get("year") or extract_year(extmeta)

    entry = OrderedDict([
        ("id", rec["pageid"]),
        ("title", title),
        ("artist", artist_out),
        ("year", year),
        ("categories", sorted(set(rec.get("keys", [])))),
        ("width", width),
        ("height", height),
        ("mime", ii.get("mime")),
        ("thumb_mime", thumbmime),
        ("url", clean_url(thumburl)),      # display URL (API thumbnail @ thumb_width)
        ("url_original", clean_url(ii.get("url"))),  # provenance only
        ("sha1", ii.get("sha1")),
        ("pinned", OrderedDict([
            ("page_lastrevid", rec.get("lastrevid")),
            ("image_timestamp", ii.get("timestamp")),
            ("image_sha1", ii.get("sha1")),
        ])),
        ("license", OrderedDict([
            ("source_country_template", source_tpl),
            ("us_template", us_tpl),
            ("source_templates_all", src_hits),
            ("us_templates_all", us_hits),
            ("term_rule", term),
            ("notes", notes),
        ])),
    ])
    return {"verdict": "accept", "reason": "ok", "entry": entry}


# --------------------------- network (build-time only) ---------------------------

class HarvestError(RuntimeError):
    """Fatal harvest failure. Propagates to main(), which then writes NO manifest
    (B2: never emit a 'complete' manifest from partial/interrupted data)."""


def _get(params, ua):
    params = dict(params); params["format"] = "json"
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=50) as r:
                d = json.load(r)
            # fail-closed: an API error/warning, or a structurally-unexpected shape,
            # must NOT be mistaken for an empty-but-complete page (that would silently
            # truncate pagination and let partial data be published as complete).
            if not isinstance(d, dict):
                raise HarvestError("unexpected API response type: %s" % type(d).__name__)
            if "error" in d:
                raise HarvestError("API error: %s" % json.dumps(d["error"])[:300])
            if "warnings" in d:
                # a module/parameter warning (e.g. a disabled module) can mean the
                # returned data is incomplete -> treat as fatal, never silently skip.
                raise HarvestError("API warnings: %s" % json.dumps(d["warnings"])[:300])
            return d
        except HarvestError:
            raise
        except Exception as ex:
            last = ex
            if attempt == 2:
                break
            time.sleep(1.5)
    raise HarvestError("request failed after 3 attempts: %s" % last)


def _subcats(cat, ua, delay):
    out, cont = [], {}
    while True:
        p = {"action": "query", "list": "categorymembers", "cmtitle": cat,
             "cmtype": "subcat", "cmlimit": "500"}
        p.update(cont)
        d = _get(p, ua)
        if "query" not in d and "batchcomplete" not in d and "continue" not in d:
            raise HarvestError("subcat %s: response lacks query/batchcomplete/continue "
                               "(possible truncation)" % cat)
        out += [m["title"] for m in d.get("query", {}).get("categorymembers", [])]
        if "continue" in d:
            cont = d["continue"]; time.sleep(delay)
        elif "batchcomplete" in d:
            break
        else:
            raise HarvestError("subcat %s: pagination ended with no batchcomplete "
                               "(possible truncation)" % cat)
    return out


def _harvest(cat, key, pages, cfg, ua):
    """Drain the full file list + ALL template pages (tlcontinue) for one category."""
    delay = cfg["config"]["traversal"]["request_delay_s"]
    tw = cfg["config"]["thumb_width"]
    cont = {}
    while True:
        p = {"action": "query", "generator": "categorymembers", "gcmtitle": cat,
             "gcmtype": "file", "gcmlimit": "50",
             "prop": "imageinfo|templates|info",
             "iiprop": "url|size|mime|thumbmime|extmetadata|timestamp|sha1",
             "iiurlwidth": str(tw), "tllimit": "500", "tlnamespace": "10"}
        p.update(cont)
        d = _get(p, ua)
        if "query" not in d and "batchcomplete" not in d and "continue" not in d:
            raise HarvestError("harvest %s: response lacks query/batchcomplete/continue "
                               "(possible truncation)" % cat)
        for pid, pg in d.get("query", {}).get("pages", {}).items():
            e = pages.setdefault(pid, {"pageid": int(pg.get("pageid", pid)),
                                       "title": pg.get("title"),
                                       "lastrevid": pg.get("lastrevid"),
                                       "templates": [], "imageinfo": None,
                                       "source_categories": set(), "keys": set(),
                                       "_tplset": set()})
            e["source_categories"].add(cat)
            e["keys"].add(key)
            for t in pg.get("templates", []):
                e["_tplset"].add(t["title"])
            if e["imageinfo"] is None and pg.get("imageinfo"):
                e["imageinfo"] = pg["imageinfo"][0]
            if pg.get("lastrevid"):
                e["lastrevid"] = pg["lastrevid"]
        if "continue" in d:
            cont = d["continue"]; time.sleep(delay)
        elif "batchcomplete" in d:
            break
        else:
            raise HarvestError("harvest %s: pagination ended with no batchcomplete "
                               "(possible truncation)" % cat)


def harvest_all(cfg):
    ua = cfg["config"]["user_agent"]
    tconf = cfg["config"]["traversal"]
    max_depth = tconf["max_depth"]
    cap = tconf["max_categories_per_seed"]
    delay = tconf["request_delay_s"]
    pages = {}
    for seed in cfg["seeds"]:
        key, root = seed["key"], seed["category"]
        visited, frontier, chosen = set(), [(root, 0)], []
        while frontier and len(chosen) < cap:
            cat, depth = frontier.pop(0)
            if cat in visited:
                continue
            visited.add(cat); chosen.append(cat)
            if depth < max_depth:
                # B2: a subcategory enumeration failure means the traversal is
                # INCOMPLETE -> fail loudly; never silently harvest a partial tree.
                try:
                    for sc in _subcats(cat, ua, delay):
                        if sc not in visited:
                            frontier.append((sc, depth + 1))
                except Exception as ex:
                    raise HarvestError("subcat enumeration failed for %s: %s" % (cat, ex))
            time.sleep(delay * 0.5)
        for cat in chosen:
            # B2: any harvest/pagination failure aborts the whole run. We must never
            # emit a 'complete' manifest built from partial data.
            try:
                _harvest(cat, key, pages, cfg, ua)
            except Exception as ex:
                raise HarvestError("harvest failed for %s: %s" % (cat, ex))
        sys.stderr.write("seed %-28s cats=%d files-so-far=%d\n" % (key, len(chosen), len(pages)))
    # finalize record shape for evaluate()
    for e in pages.values():
        e["templates"] = [{"title": t} for t in e["_tplset"]]
        e["source_categories"] = sorted(e["source_categories"])
        e["keys"] = sorted(e["keys"])
        del e["_tplset"]
    return pages


FLOOR = 50  # minimum accepted non-icon entries for a real (status=OK) manifest


def _atomic_write(path, obj):
    """temp -> fsync-less rename; a crash mid-write never leaves a torn target."""
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1, ensure_ascii=False)
    os.replace(tmp, path)


def _shortfall_path(out):
    root, ext = os.path.splitext(out)
    return root + ".shortfall" + (ext or ".json")


def build_and_write(pages, cfg, out, floor=FLOOR, write_evidence=True, evidence_dir=None):
    """Evaluate harvested pages and persist the result. PURE of network.

    B2 contract: the real manifest (`out`, status=OK) is written ONLY when
    count >= floor. A shortfall writes a clearly-labeled SEPARATE file
    (`*.shortfall.json`) and NEVER touches / clobbers an existing good manifest.
    Returns (exit_code, manifest). exit_code: 0 = OK written, 2 = shortfall.
    """
    ev_dir = evidence_dir or os.path.join(HERE, "evidence")
    accepted, accepted_pages, rejects, icon_rejected = [], [], {}, 0
    for e in pages.values():
        res = evaluate(e, cfg)
        if res["verdict"] == "accept":
            accepted.append(res["entry"])
            accepted_pages.append(e)
        else:
            r = res["reason"].split(":")[0].split(" ")[0]
            rejects[r] = rejects.get(r, 0) + 1
            if res["reason"].startswith("excluded-category"):
                icon_rejected += 1

    accepted.sort(key=lambda x: (x["categories"], -x["width"]))
    widths = sorted(x["width"] for x in accepted)
    ok = len(accepted) >= floor
    manifest = OrderedDict([
        ("schema", SCHEMA),
        ("generated_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())),
        ("width_min", cfg["config"]["width_min"]),
        ("gate", {"source_templates": sorted(SOURCE_TEMPLATES),
                  "us_templates": sorted(US_TEMPLATES),
                  "self_contained_source_templates": sorted(SELF_CONTAINED_SOURCE),
                  "excluded_license_prefixes": sorted(_EXCLUDED_LICENSE_PREFIXES),
                  "excluded_license_exact": sorted(EXCLUDED_LICENSE_EXACT),
                  "excluded_category_substrings": cfg.get("exclude_category_substrings", []),
                  "life_based_overrides": sorted((cfg.get("life_based_overrides") or {}).keys()),
                  "note": "icons excluded in P1 (separate legal basis, deferred to P3); "
                          "life-based-only files rejected unless pageid-curated (empty in P1)"}),
        ("count", len(accepted)),
        ("status", "OK" if ok else "SHORTFALL"),
        ("entries", accepted),
    ])

    if ok:
        _atomic_write(out, manifest)
        if write_evidence:
            os.makedirs(ev_dir, exist_ok=True)
            for e in accepted_pages:
                _atomic_write(os.path.join(ev_dir, "%s.json" % e["pageid"]),
                              {"pageid": e["pageid"], "title": e["title"],
                               "lastrevid": e.get("lastrevid"),
                               "templates": sorted(norm_templates(e)),
                               "imageinfo": e["imageinfo"],
                               "source_categories": e["source_categories"],
                               "keys": e["keys"]})
        target = out
    else:
        # fail-closed: do NOT overwrite the known-good manifest with a short run.
        target = _shortfall_path(out)
        _atomic_write(target, manifest)

    median = widths[len(widths) // 2] if widths else 0
    sys.stderr.write("\n=== RESULT ===\n")
    sys.stderr.write("accepted (non-icon): %d  floor=%d  status=%s\n"
                     % (len(accepted), floor, manifest["status"]))
    sys.stderr.write("icon files rejected (excluded): %d\n" % icon_rejected)
    sys.stderr.write("width min/median/max: %s / %s / %s\n" %
                     (widths[0] if widths else 0, median, widths[-1] if widths else 0))
    sys.stderr.write("rejection breakdown: %s\n" % json.dumps(rejects, sort_keys=True))
    if ok:
        sys.stderr.write("manifest: %s\n" % target)
    else:
        sys.stderr.write("SHORTFALL (%d < %d): real manifest %s left UNTOUCHED; "
                         "shortfall written to %s\n" % (len(accepted), floor, out, target))
    return (0 if ok else 2), manifest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, "art-manifest.json"))
    ap.add_argument("--curation", default=os.path.join(HERE, "curation.json"))
    ap.add_argument("--no-evidence", action="store_true")
    args = ap.parse_args()

    with open(args.curation) as f:
        cfg = json.load(f)

    try:
        pages = harvest_all(cfg)
    except HarvestError as ex:
        # B2: partial / interrupted harvest -> FAIL loudly; write NOTHING, so no
        # 'complete' manifest is ever emitted from partial data and the committed
        # known-good manifest is left untouched.
        sys.stderr.write("\nFATAL: harvest aborted, manifest NOT written: %s\n" % ex)
        return 3
    sys.stderr.write("\nunique files harvested: %d\n" % len(pages))

    code, _ = build_and_write(pages, cfg, args.out, floor=FLOOR,
                              write_evidence=not args.no_evidence)
    return code


if __name__ == "__main__":
    sys.exit(main())
