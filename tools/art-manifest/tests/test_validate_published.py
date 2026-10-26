#!/usr/bin/env python3
"""Offline unit tests for validate_published.py (Step 3 published-set validation).

ZERO NETWORK. A StubFetcher serves synthetic manifests/images from an in-memory map so the
schema, naming, image-byte, and driver logic are exercised without a server. The real
HttpsFetcher is never invoked here.

Run:  python3 -m unittest discover -s tools/art-manifest/tests -v
"""
import email.message, io, json, os, sys, unittest, urllib.request
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
sys.path.insert(0, TOOL)
import validate_published as vp  # noqa: E402
from PIL import Image            # noqa: E402

BASE = vp.BASE_URL


def jpeg(w, h, color=(70, 90, 110)):
    im = Image.new("RGB", (w, h), color)
    buf = io.BytesIO()
    im.save(buf, format="JPEG", quality=85)
    return buf.getvalue()


def entry_for(eid, data, title="T", year="1891", categories=None):
    s = vp.sha1_hex(data)
    return OrderedDict([
        ("id", eid), ("title", title), ("year", year),
        ("categories", categories or ["era:19c"]),
        ("file", "%d-%s.jpg" % (eid, s[:12])), ("sha1", s),
    ])


def manifest_for(entries, base_url=BASE, generated_at="2026-10-26T00:00:00Z"):
    return OrderedDict([("base_url", base_url), ("generated_at", generated_at),
                        ("entries", list(entries))])


class StubFetcher(object):
    """Serves a map {url: (status, body)}; unknown urls -> 404. Can be told to raise."""
    def __init__(self, routes, raise_on=None):
        self.routes = routes
        self.raise_on = raise_on or set()

    def fetch(self, url):
        if url in self.raise_on:
            raise vp.FetchError("boom %s" % url)
        return self.routes.get(url, (404, b""))


def build_routes(entries, manifest=None, manifest_url=BASE + "manifest.json"):
    manifest = manifest if manifest is not None else manifest_for(entries)
    raw = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
    routes = {manifest_url: (200, raw)}
    return routes, manifest, raw


class PureSchema(unittest.TestCase):
    def test_valid_manifest_doc_no_errors(self):
        e = entry_for(1, jpeg(100, 80))
        m = manifest_for([e])
        raw = json.dumps(m).encode()
        self.assertEqual(vp.validate_manifest_doc(m, raw), [])

    def test_wrong_base_url_rejected(self):
        e = entry_for(1, jpeg(100, 80))
        m = manifest_for([e], base_url="https://evil.example/")
        errs = vp.validate_manifest_doc(m, json.dumps(m).encode())
        self.assertTrue(any("base_url" in x for x in errs))

    def test_oversize_manifest_rejected(self):
        e = entry_for(1, jpeg(10, 10))
        m = manifest_for([e])
        errs = vp.validate_manifest_doc(m, b"x" * (vp.MANIFEST_MAX_BYTES + 1))
        self.assertTrue(any("MANIFEST_MAX_BYTES" in x for x in errs))

    def test_too_many_entries_rejected(self):
        e = entry_for(1, jpeg(10, 10))
        m = manifest_for([e] * (vp.MAX_MANIFEST_ENTRIES + 1))
        errs = vp.validate_manifest_doc(m, json.dumps(m).encode())
        self.assertTrue(any("MAX_MANIFEST_ENTRIES" in x for x in errs))

    def test_duplicate_id_rejected(self):
        data = jpeg(50, 50)
        m = manifest_for([entry_for(1, data), entry_for(1, data)])
        errs = vp.validate_manifest_doc(m, json.dumps(m).encode())
        self.assertTrue(any("duplicate id" in x for x in errs))

    def test_unsafe_file_rejected(self):
        e = entry_for(1, jpeg(10, 10))
        e["file"] = "../../etc/passwd"
        m = manifest_for([e])
        errs = vp.validate_manifest_doc(m, json.dumps(m).encode())
        self.assertTrue(any("unsafe/invalid file" in x for x in errs))

    def test_missing_field_rejected(self):
        e = entry_for(1, jpeg(10, 10))
        del e["sha1"]
        m = manifest_for([e])
        errs = vp.validate_manifest_doc(m, json.dumps(m).encode())
        self.assertTrue(any("missing field 'sha1'" in x for x in errs))

    def test_name_not_matching_sha1_rejected(self):
        e = entry_for(1, jpeg(10, 10))
        e["file"] = "1-000000000000.jpg"  # valid shape, wrong digest prefix
        m = manifest_for([e])
        errs = vp.validate_manifest_doc(m, json.dumps(m).encode())
        self.assertTrue(any("does not embed sha1 prefix" in x for x in errs))

    def test_file_name_matches_sha1_helper(self):
        self.assertTrue(vp.file_name_matches_sha1("9-abcdef012345.jpg", "abcdef012345" + "0" * 28))
        self.assertFalse(vp.file_name_matches_sha1("9-abcdef012345.jpg", "ffffff012345" + "0" * 28))
        self.assertFalse(vp.file_name_matches_sha1("9-abcdef012345.jpg", "nothex"))


class ImageBytes(unittest.TestCase):
    def test_good_image_passes(self):
        data = jpeg(200, 150)
        self.assertEqual(vp.validate_image_bytes(data, entry_for(1, data)), [])

    def test_sha1_mismatch_flagged(self):
        data = jpeg(200, 150)
        e = entry_for(1, data)
        e["sha1"] = "0" * 40
        errs = vp.validate_image_bytes(data, e)
        self.assertTrue(any("sha1" in x for x in errs))

    def test_oversize_image_flagged(self):
        # temporarily tighten the cap so a normal JPEG trips it (restored in finally)
        data = jpeg(300, 300)
        e = entry_for(1, data)
        saved = vp.IMAGE_MAX_BYTES
        try:
            vp.IMAGE_MAX_BYTES = 10
            errs = vp.validate_image_bytes(data, e)
        finally:
            vp.IMAGE_MAX_BYTES = saved
        self.assertTrue(any("IMAGE_MAX_BYTES" in x for x in errs))
        self.assertEqual(saved, 4 * 1024 * 1024)

    def test_oversize_long_edge_flagged(self):
        data = jpeg(2000, 100)  # long edge 2000 > 1920
        e = entry_for(1, data)
        errs = vp.validate_image_bytes(data, e)
        self.assertTrue(any("long edge" in x for x in errs))

    def test_undecodable_flagged(self):
        junk = b"not an image at all"
        e = entry_for(1, jpeg(10, 10))
        e["sha1"] = vp.sha1_hex(junk)
        e["file"] = "1-%s.jpg" % e["sha1"][:12]
        errs = vp.validate_image_bytes(junk, e)
        self.assertTrue(any("undecodable" in x for x in errs))


class Driver(unittest.TestCase):
    def test_full_valid_set_ok(self):
        es = [entry_for(1, jpeg(100, 80)), entry_for(2, jpeg(90, 70))]
        routes, _, _ = build_routes(es)
        for e in es:
            data = jpeg(100, 80) if e["id"] == 1 else jpeg(90, 70)
            routes[BASE + "images/" + e["file"]] = (200, data)
        ok, report = vp.validate(BASE + "manifest.json", StubFetcher(routes))
        self.assertTrue(ok, report)
        self.assertIn("2 entries", report)

    def test_manifest_404(self):
        ok, report = vp.validate(BASE + "manifest.json", StubFetcher({}))
        self.assertFalse(ok)
        self.assertIn("HTTP 404", report)

    def test_manifest_fetch_error(self):
        url = BASE + "manifest.json"
        ok, report = vp.validate(url, StubFetcher({}, raise_on={url}))
        self.assertFalse(ok)
        self.assertIn("fetch failed", report)

    def test_image_404_flagged(self):
        es = [entry_for(1, jpeg(100, 80))]
        routes, _, _ = build_routes(es)  # image route intentionally absent
        ok, report = vp.validate(BASE + "manifest.json", StubFetcher(routes))
        self.assertFalse(ok)
        self.assertIn("HTTP 404", report)

    def test_image_sha1_mismatch_flagged(self):
        es = [entry_for(1, jpeg(100, 80))]
        routes, _, _ = build_routes(es)
        routes[BASE + "images/" + es[0]["file"]] = (200, jpeg(100, 80, (1, 2, 3)))  # diff bytes
        ok, report = vp.validate(BASE + "manifest.json", StubFetcher(routes))
        self.assertFalse(ok)
        self.assertIn("sha1", report)

    def test_bad_json_manifest(self):
        ok, report = vp.validate(BASE + "manifest.json",
                                 StubFetcher({BASE + "manifest.json": (200, b"<html>nope")}))
        self.assertFalse(ok)
        self.assertIn("does not parse", report)


class DirFetcherTest(unittest.TestCase):
    def test_dir_fetcher_serves_and_404s(self):
        import tempfile
        root = tempfile.mkdtemp()
        os.makedirs(os.path.join(root, "images"))
        with open(os.path.join(root, "images", "x.jpg"), "wb") as f:
            f.write(b"hi")
        fetcher = vp.DirFetcher(root)
        self.assertEqual(fetcher.fetch(BASE + "images/x.jpg"), (200, b"hi"))
        self.assertEqual(fetcher.fetch(BASE + "images/missing.jpg"), (404, b""))

    def test_dir_fetcher_rejects_foreign_url(self):
        root = os.getcwd()
        with self.assertRaises(vp.FetchError):
            vp.DirFetcher(root).fetch("https://evil.example/x")


class HttpsDowngrade(unittest.TestCase):
    """COMMIT_REVIEW P2 (HTTPS enforcement permits downgrade): the fetcher must hard-reject a
    non-HTTPS request URL and refuse any redirect that would downgrade to a non-HTTPS target —
    no silent HTTPS->HTTP downgrade while still reporting 'TLS verified'. ZERO NETWORK."""

    def test_fetch_rejects_non_https_request_url(self):
        f = vp.HttpsFetcher()
        for bad in ("http://art.jmwork.ru/manifest.json",
                    "ftp://art.jmwork.ru/x", "HTTP://art.jmwork.ru/x"):
            with self.assertRaises(vp.FetchError):
                f.fetch(bad)  # raises before any network I/O

    def test_redirect_handler_refuses_non_https_target(self):
        h = vp._HttpsOnlyRedirectHandler()
        req = urllib.request.Request("https://art.jmwork.ru/manifest.json")
        hdrs = email.message.Message()
        for bad in ("http://example.invalid/manifest.json", "ftp://x/y", ""):
            with self.assertRaises(vp.FetchError):
                h.redirect_request(req, io.BytesIO(b""), 302, "Found", hdrs, bad)

    def test_redirect_handler_allows_https_target(self):
        h = vp._HttpsOnlyRedirectHandler()
        req = urllib.request.Request("https://art.jmwork.ru/manifest.json")
        hdrs = email.message.Message()
        out = h.redirect_request(req, io.BytesIO(b""), 302, "Found", hdrs,
                                 "https://art.jmwork.ru/elsewhere.json")
        self.assertIsInstance(out, urllib.request.Request)
        self.assertTrue(out.full_url.startswith("https://"))

    def test_opener_is_bound_with_tls_and_redirect_guard(self):
        f = vp.HttpsFetcher()
        handlers = f.opener.handlers
        self.assertTrue(any(isinstance(h, vp._HttpsOnlyRedirectHandler) for h in handlers))
        self.assertTrue(any(isinstance(h, urllib.request.HTTPSHandler) for h in handlers))


if __name__ == "__main__":
    unittest.main(verbosity=2)
