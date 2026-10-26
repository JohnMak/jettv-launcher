#!/usr/bin/env python3
"""Step 3 validation: assert the published art set is well-formed and intact.

Validates a published manifest (staged or live) and every image it references:
  * manifest fetches 200 over HTTPS (TLS verified) and parses as JSON,
  * byte size <= MANIFEST_MAX_BYTES, entry count <= MAX_MANIFEST_ENTRIES,
  * top-level base_url is EXACTLY https://art.jmwork.ru/ (fail-closed; the manifest can
    never redirect the client elsewhere),
  * every entry carries {id,title,year,categories,file,sha1}; ids are unique; `file` is a
    single safe path segment (FILE_RE) whose name embeds the declared sha1 prefix
    (immutability binding),
  * every images/<file> fetches 200, byte size <= IMAGE_MAX_BYTES, decodes as an image,
    long edge <= 1920, and its bytes sha1-match the manifest's declared digest.

This module OWNS the published-set contract constants (publish.py imports them) so producer
and validator can never drift. Pure helpers (schema / naming / image-byte checks) take plain
bytes and are unit-tested offline; the network boundary is an injectable `fetcher` so the
same logic validates a LIVE HTTPS endpoint, a STAGED endpoint, or a local directory.

Append-only retention helper (H1, Rev5): `assert_append_only(before, after)` proves a publish
cycle only ever ADDED server files — used by the retention scenario test.

Usage:
    python3 validate_published.py                               # validate live manifest.json
    python3 validate_published.py --url https://art.jmwork.ru/manifest.json.staged
    python3 validate_published.py --dir WEBROOT                 # validate a local webroot
"""
import argparse, hashlib, io, json, os, re, ssl, sys, urllib.request
from collections import OrderedDict

# ============ published-set contract (single source of truth) ============================
BASE_URL = "https://art.jmwork.ru/"
IMAGES_PREFIX = "images/"
MANIFEST_NAME = "manifest.json"
IMAGE_MAX_EDGE = 1920
IMAGE_MAX_BYTES = 4 * 1024 * 1024
MANIFEST_MAX_BYTES = 256 * 1024
MAX_MANIFEST_ENTRIES = 500
# one safe path segment: no "/", no "..", <=104 chars, .jpg
FILE_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.jpg$")
SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
ENTRY_FIELDS = ("id", "title", "year", "categories", "file", "sha1")
HTTP_TIMEOUT = 30


class FetchError(RuntimeError):
    """Transport/TLS failure reaching an endpoint (recorded as a validation failure)."""


# ================================ pure helpers ============================================

def sha1_hex(data):
    return hashlib.sha1(data).hexdigest()


def file_name_matches_sha1(file_name, sha1):
    """Immutability binding: the published name embeds the derivative sha1 prefix
    (`...-<sha1[0:12]>.jpg`). A re-encode with different bytes yields a different sha1 and
    thus a different name — an in-place overwrite can never masquerade as the same file."""
    if not SHA1_RE.match(sha1 or ""):
        return False
    return file_name.endswith("-%s.jpg" % sha1[:12])


def image_dimensions(data):
    """(w, h) of JPEG/image bytes via Pillow. Raises ValueError on undecodable bytes."""
    try:
        from PIL import Image
    except ImportError as ex:  # pragma: no cover - environment guard
        raise ValueError("Pillow (PIL) required to decode images: %s" % ex)
    try:
        with Image.open(io.BytesIO(data)) as im:
            im.load()
            return im.size
    except Exception as ex:
        raise ValueError("undecodable image: %s" % ex)


def validate_manifest_doc(doc, raw_bytes, base_url=BASE_URL):
    """Pure schema validation. Returns a list of human-readable error strings ([] = valid).
    Checks byte cap, base_url exactness, entry-count cap, per-entry schema, unique ids, safe
    file names, sha1 well-formedness, and the name<->sha1 immutability binding."""
    errs = []
    if len(raw_bytes) > MANIFEST_MAX_BYTES:
        errs.append("manifest %d bytes > MANIFEST_MAX_BYTES %d"
                    % (len(raw_bytes), MANIFEST_MAX_BYTES))
    if not isinstance(doc, dict):
        return errs + ["manifest is not a JSON object"]
    if doc.get("base_url") != base_url:
        errs.append("base_url %r != %r (exact match required)" % (doc.get("base_url"), base_url))
    entries = doc.get("entries")
    if not isinstance(entries, list):
        return errs + ["entries is not a list"]
    if len(entries) > MAX_MANIFEST_ENTRIES:
        errs.append("entry count %d > MAX_MANIFEST_ENTRIES %d"
                    % (len(entries), MAX_MANIFEST_ENTRIES))
    seen = set()
    for i, e in enumerate(entries):
        if not isinstance(e, dict):
            errs.append("entry[%d] is not an object" % i)
            continue
        for field in ENTRY_FIELDS:
            if field not in e:
                errs.append("entry[%d] (id=%s) missing field %r" % (i, e.get("id"), field))
        eid = e.get("id")
        if eid in seen:
            errs.append("duplicate id %s" % eid)
        seen.add(eid)
        if not isinstance(e.get("categories"), list):
            errs.append("entry id=%s categories is not a list" % eid)
        f = e.get("file", "")
        if not isinstance(f, str) or not FILE_RE.match(f):
            errs.append("entry id=%s unsafe/invalid file %r" % (eid, f))
        s = e.get("sha1", "")
        if not isinstance(s, str) or not SHA1_RE.match(s):
            errs.append("entry id=%s invalid sha1 %r" % (eid, s))
        elif isinstance(f, str) and not file_name_matches_sha1(f, s):
            errs.append("entry id=%s file %r does not embed sha1 prefix %s"
                        % (eid, f, s[:12]))
    return errs


def validate_image_bytes(data, entry):
    """Pure per-image validation against the entry's declared sha1/file. Returns error list."""
    errs = []
    f = entry.get("file")
    declared = entry.get("sha1", "")
    if len(data) > IMAGE_MAX_BYTES:
        errs.append("images/%s %d bytes > IMAGE_MAX_BYTES %d" % (f, len(data), IMAGE_MAX_BYTES))
    actual = sha1_hex(data)
    if actual != declared:
        errs.append("images/%s sha1 %s != declared %s" % (f, actual, declared))
    try:
        w, h = image_dimensions(data)
        if max(w, h) > IMAGE_MAX_EDGE:
            errs.append("images/%s long edge %d > %d" % (f, max(w, h), IMAGE_MAX_EDGE))
    except ValueError as ex:
        errs.append("images/%s %s" % (f, ex))
    return errs


def assert_append_only(before, after):
    """Retention invariant (H1): the server file set only ever grows. Returns error list;
    [] iff `after` is a superset of `before` (nothing was pruned/deleted)."""
    missing = set(before) - set(after)
    if missing:
        return ["APPEND-ONLY VIOLATION: file(s) removed by publish cycle: %s"
                % sorted(missing)]
    return []


# ================================ fetchers (network boundary) =============================

class _HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Fail-closed against TLS downgrade: urllib follows redirects by default, so an
    HTTPS->HTTP (or any non-HTTPS) redirect would silently downgrade the transport while the
    validator still reports 'TLS verified'. Refuse any redirect whose target is not HTTPS."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if not (newurl or "").lower().startswith("https://"):
            raise FetchError("refusing redirect to non-HTTPS URL: %s" % newurl)
        return urllib.request.HTTPRedirectHandler.redirect_request(
            self, req, fp, code, msg, headers, newurl)


class HttpsFetcher(object):
    """Real HTTPS fetcher with certificate verification. fetch(url) -> (status, body).
    A TLS/transport failure raises FetchError (recorded as a validation failure).

    Hard HTTPS-only (COMMIT_REVIEW P2 — downgrade): the request URL MUST be https, redirects
    to any non-HTTPS target are refused, and the FINAL responding URL must still be https."""

    def __init__(self, timeout=HTTP_TIMEOUT):
        self.timeout = timeout
        self.ctx = ssl.create_default_context()  # verifies hostname + chain
        # Own opener: bind TLS context + the downgrade-refusing redirect handler (do NOT pass
        # context= to urlopen separately — the opener's HTTPSHandler carries it).
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPSHandler(context=self.ctx),
            _HttpsOnlyRedirectHandler())

    def fetch(self, url):
        if not (url or "").lower().startswith("https://"):
            raise FetchError("refusing non-HTTPS request URL: %s" % url)
        req = urllib.request.Request(url, headers={"User-Agent": "art-validate/1.0"})
        try:
            with self.opener.open(req, timeout=self.timeout) as r:
                final = r.geturl()
                if not (final or "").lower().startswith("https://"):
                    raise FetchError("response resolved to non-HTTPS URL: %s" % final)
                return r.getcode(), r.read()
        except urllib.error.HTTPError as ex:
            return ex.code, b""
        except FetchError:
            raise
        except Exception as ex:
            raise FetchError("%s: %s" % (url, ex))


class DirFetcher(object):
    """Validate a local webroot dir as if served under base_url. fetch(url) -> (status, body)."""

    def __init__(self, root, base_url=BASE_URL):
        self.root = root
        self.base_url = base_url

    def fetch(self, url):
        if not url.startswith(self.base_url):
            raise FetchError("url %r outside base_url %r" % (url, self.base_url))
        rel = url[len(self.base_url):]
        path = os.path.join(self.root, *rel.split("/"))
        if not os.path.isfile(path):
            return 404, b""
        with open(path, "rb") as f:
            return 200, f.read()


# ================================== validation driver =====================================

def validate(manifest_url, fetcher, base_url=BASE_URL):
    """Validate a manifest endpoint + every image it references. Returns (ok, report)."""
    ok, report, _raw = validate_detailed(manifest_url, fetcher, base_url)
    return ok, report


def validate_detailed(manifest_url, fetcher, base_url=BASE_URL):
    """Like validate() but ALSO returns the exact manifest bytes the validator fetched.

    Returns (ok, report, raw). `raw` is the precise byte image the validator pulled from the
    endpoint and ran every check against (b"" if the manifest fetch itself failed). publish.py
    binds promotion to THESE bytes (COMMIT_REVIEW P1): the artifact that was validated is then
    provably the artifact that gets promoted, so a redirect or server-side swap that makes
    validation PASS on a DIFFERENT manifest than the staged file cannot slip a different
    manifest into the live pointer."""
    errs = []
    try:
        status, raw = fetcher.fetch(manifest_url)
    except FetchError as ex:
        return False, "manifest fetch failed: %s" % ex, b""
    if status != 200:
        return False, "manifest %s -> HTTP %s" % (manifest_url, status), raw
    try:
        doc = json.loads(raw.decode("utf-8"))
    except Exception as ex:
        return False, "manifest does not parse as JSON: %s" % ex, raw

    errs.extend(validate_manifest_doc(doc, raw, base_url))
    entries = doc.get("entries") if isinstance(doc, dict) else None
    checked = 0
    if isinstance(entries, list):
        for e in entries:
            if not isinstance(e, dict) or not isinstance(e.get("file"), str):
                continue  # schema error already recorded above
            img_url = base_url + IMAGES_PREFIX + e["file"]
            try:
                istatus, idata = fetcher.fetch(img_url)
            except FetchError as ex:
                errs.append("images/%s fetch failed: %s" % (e["file"], ex))
                continue
            if istatus != 200:
                errs.append("images/%s -> HTTP %s" % (e["file"], istatus))
                continue
            errs.extend(validate_image_bytes(idata, e))
            checked += 1

    n = len(entries) if isinstance(entries, list) else 0
    if errs:
        report = ("FAIL (%d error(s)) for %s — %d/%d images reached:\n  - %s"
                  % (len(errs), manifest_url, checked, n, "\n  - ".join(errs)))
        return False, report, raw
    report = ("OK: %s valid — base_url exact, %d entries, %d/%d images 200 + sha1-match + "
              "<=%dpx + <=%dB; TLS verified." % (manifest_url, n, checked, n,
                                                 IMAGE_MAX_EDGE, IMAGE_MAX_BYTES))
    return True, report, raw


def validate_url(manifest_url, fetcher=None, base_url=BASE_URL):
    """Convenience wrapper over the real HTTPS fetcher (what publish.py calls for staged).
    Returns (ok, report, raw) — `raw` is the exact fetched manifest bytes that publish.py binds
    promotion to (COMMIT_REVIEW P1); one fetch, validated and byte-bound, no re-fetch window."""
    return validate_detailed(manifest_url, fetcher or HttpsFetcher(), base_url)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--url", default=BASE_URL + MANIFEST_NAME,
                    help="manifest URL (default: live manifest.json)")
    ap.add_argument("--dir", default=None,
                    help="validate a LOCAL webroot dir instead of fetching over HTTPS")
    args = ap.parse_args(argv)

    if args.dir:
        fetcher = DirFetcher(args.dir)
    else:
        fetcher = HttpsFetcher()
    ok, report = validate(args.url, fetcher)
    sys.stderr.write(report + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
