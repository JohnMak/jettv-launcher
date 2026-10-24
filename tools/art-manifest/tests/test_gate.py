#!/usr/bin/env python3
"""Offline fail-closed gate tests for the art-manifest generator.

ZERO NETWORK. Positive/negative fixtures under tests/fixtures/ are real Commons
imageinfo+templates responses captured at build time; mutation cases are derived
from a real accepted base so boundaries are exercised against genuine data shapes.

Run:  python3 -m unittest discover -s tools/art-manifest/tests -v
"""
import copy, json, os, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
FX = os.path.join(HERE, "fixtures")
sys.path.insert(0, TOOL)
import generate  # noqa: E402

with open(os.path.join(TOOL, "curation.json")) as f:
    CFG = json.load(f)


def fx(name):
    with open(os.path.join(FX, name + ".json")) as f:
        return json.load(f)


def mutate(base, templates=None, width=None, thumbmime="__keep__",
           thumburl="__keep__", cats=None):
    r = copy.deepcopy(base)
    if templates is not None:
        r["templates"] = [{"title": "Template:" + t} for t in templates]
    ii = r["imageinfo"]
    if width is not None:
        ii["width"] = width
    if thumbmime != "__keep__":
        ii["thumbmime"] = thumbmime
    if thumburl != "__keep__":
        ii["thumburl"] = thumburl
    if cats is not None:
        r["source_categories"] = cats
    return r


def verdict(rec):
    return generate.evaluate(rec, CFG)


class RealFixtures(unittest.TestCase):
    def test_accept_real_pair(self):
        res = verdict(fx("accept_real"))
        self.assertEqual(res["verdict"], "accept", res["reason"])
        lic = res["entry"]["license"]
        self.assertTrue(lic["source_country_template"])
        self.assertTrue(lic["us_template"])

    def test_accepted_entry_carries_thumbmime(self):
        # coordinator requirement: thumbmime presence on accepted entries
        res = verdict(fx("accept_real"))
        self.assertIn(res["entry"]["thumb_mime"], ("image/jpeg", "image/png"))

    def test_conflict_not_pd_rejected(self):
        # real file carrying Not-PD-US-expired-min-year alongside PD-US-expired-text
        res = verdict(fx("conflict_notpd"))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("conflicting-license"), res["reason"])

    def test_real_no_us_template_rejected(self):
        # real 3135px PD-old-70 file, no accepted US template (passes 3000 width bar)
        res = verdict(fx("no_us_real_3135"))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-accepted-us-template")

    def test_real_ccbysa_rejected(self):
        res = verdict(fx("ccbysa"))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-accepted-source-template")

    def test_real_icon_excluded(self):
        res = verdict(fx("icon_real"))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("excluded-category"), res["reason"])

    def test_real_pdart_400px_rejected(self):
        res = verdict(fx("pdart_composite"))
        self.assertEqual(res["verdict"], "reject")  # 400px -> width reject


class WidthBoundary(unittest.TestCase):
    def setUp(self):
        self.base = fx("accept_real")

    def test_3000_accepted(self):
        self.assertEqual(verdict(mutate(self.base, width=3000))["verdict"], "accept")

    def test_2999_rejected(self):
        res = verdict(mutate(self.base, width=2999))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("width<3000"), res["reason"])


class CompositeUsTemplates(unittest.TestCase):
    """CEO-approved B correction: each composite US template is a valid US basis
    (in addition to a source-country template)."""
    def setUp(self):
        self.base = fx("accept_real")

    def test_each_composite_accepts(self):
        for us in ("PD-US-expired-text", "PD-old-X-expired", "PD-old-auto-expired"):
            with self.subTest(us=us):
                res = verdict(mutate(self.base, templates=["PD-RusEmpire", us]))
                self.assertEqual(res["verdict"], "accept", res["reason"])
                self.assertEqual(res["entry"]["license"]["us_template"], us)

    def test_literal_us_still_accepts(self):
        res = verdict(mutate(self.base, templates=["PD-old-100", "PD-1996"]))
        self.assertEqual(res["verdict"], "accept", res["reason"])


class FailClosedLicense(unittest.TestCase):
    def setUp(self):
        self.base = fx("accept_real")

    def test_no_templates_rejected(self):
        res = verdict(mutate(self.base, templates=[]))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-accepted-source-template")

    def test_source_only_no_us_rejected(self):
        res = verdict(mutate(self.base, templates=["PD-old-70"]))
        self.assertEqual(res["reason"], "no-accepted-us-template")

    def test_us_only_no_source_rejected(self):
        res = verdict(mutate(self.base, templates=["PD-US-expired"]))
        self.assertEqual(res["reason"], "no-accepted-source-template")

    def test_pdold70_plus_pdart_no_us_rejected(self):
        # PD-Art must NOT supply the US basis
        res = verdict(mutate(self.base, templates=["PD-old-70", "PD-Art", "PD-Art/layout"]))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-accepted-us-template")

    def test_bare_pdart_rejected(self):
        res = verdict(mutate(self.base, templates=["PD-Art", "PD-Art/en", "PD-Art/layout"]))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-accepted-source-template")

    def test_bare_pdrussia_no_us_rejected(self):
        res = verdict(mutate(self.base, templates=["PD-Russia"]))
        self.assertEqual(res["reason"], "no-accepted-us-template")

    def test_ccpdmark_with_cc_rejected(self):
        # CC-PD-Mark footer riding alongside an excluded CC basis is not a source basis
        res = verdict(mutate(self.base, templates=["CC-PD-Mark", "Cc-by-sa-4.0"]))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-accepted-source-template")

    def test_conflict_overrides_valid_pair(self):
        # a valid source+US pair is still rejected if a Not-PD-* conflict is present
        res = verdict(mutate(self.base, templates=["PD-RusEmpire", "PD-US-expired-text",
                                                   "Not-PD-US-expired-min-year"]))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("conflicting-license"))


class IconExclusion(unittest.TestCase):
    def test_icon_category_excludes_otherwise_valid_file(self):
        # isolate icon exclusion: a file that would otherwise ACCEPT, tainted by an icon category
        base = fx("accept_real")
        res = verdict(mutate(base, cats=["Category:19th-century icons of Russia"]))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("excluded-category"), res["reason"])


class ThumbFormat(unittest.TestCase):
    def setUp(self):
        self.base = fx("accept_real")

    def test_thumbmime_missing_rejected(self):
        res = verdict(mutate(self.base, thumbmime=None))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("thumbmime"), res["reason"])

    def test_thumbmime_nonraster_rejected(self):
        res = verdict(mutate(self.base, thumbmime="image/svg+xml"))
        self.assertEqual(res["verdict"], "reject")
        self.assertTrue(res["reason"].startswith("thumbmime-not-raster"), res["reason"])

    def test_no_thumburl_rejected(self):
        res = verdict(mutate(self.base, thumburl=None))
        self.assertEqual(res["verdict"], "reject")
        self.assertEqual(res["reason"], "no-thumburl")


class ManifestArtifact(unittest.TestCase):
    """Validate the actual committed deliverable, offline."""
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(TOOL, "art-manifest.json")) as f:
            cls.m = json.load(f)

    def test_schema_and_floor(self):
        self.assertEqual(self.m["schema"], 2)
        self.assertGreaterEqual(self.m["count"], 50)
        self.assertEqual(self.m["count"], len(self.m["entries"]))
        self.assertEqual(self.m["status"], "OK")

    def test_every_entry_wellformed(self):
        for e in self.m["entries"]:
            self.assertGreaterEqual(e["width"], 3000, e["id"])
            self.assertIn(e["thumb_mime"], ("image/jpeg", "image/png"), e["id"])
            self.assertTrue(e["license"]["source_country_template"], e["id"])
            self.assertTrue(e["license"]["us_template"], e["id"])
            self.assertTrue(e["sha1"], e["id"])
            self.assertTrue(e["url"], e["id"])
            self.assertNotIn("utm_", e["url"])
            for c in e["categories"]:
                self.assertNotIn("icon", c.lower(), e["id"])


class B1ExcludedLicenseVeto(unittest.TestCase):
    """B1: a valid source+US PD pair does NOT rescue a file that also carries a
    non-free / restrictive license template. Co-occurrence -> fail-closed reject."""
    def setUp(self):
        self.base = fx("accept_real")  # real PD-RusEmpire + PD-US-expired-text pair

    def _assert_vetoed(self, extra):
        res = verdict(mutate(self.base,
                             templates=["PD-RusEmpire", "PD-US-expired-text"] + extra))
        self.assertEqual(res["verdict"], "reject",
                         "expected veto with %s, got accept" % extra)
        self.assertTrue(res["reason"].startswith("excluded-license"), res["reason"])

    def test_ccbysa4_cooccurring_with_valid_pair_rejected(self):
        self._assert_vetoed(["Cc-by-sa-4.0"])

    def test_ccby4_cooccurring_with_valid_pair_rejected(self):
        self._assert_vetoed(["Cc-by-4.0"])

    def test_pdself_cooccurring_with_valid_pair_rejected(self):
        self._assert_vetoed(["PD-self"])

    def test_gfdl_cooccurring_with_valid_pair_rejected(self):
        self._assert_vetoed(["GFDL-1.2"])

    def test_unknown_cc_version_still_vetoed(self):
        # fail-closed on an unknown/future restrictive-license variant name
        self._assert_vetoed(["Cc-by-sa-9.9"])

    def test_licensed_pdart_cooccurring_rejected(self):
        # PD-Art reproduction wrapped in a CC license on the photo -> attribution duty
        self._assert_vetoed(["Licensed-PD-Art"])

    def test_licensed_pdart_two_cooccurring_rejected(self):
        # prefix match must catch the -two / layout variants, not just the exact name
        self._assert_vetoed(["Licensed-PD-Art-two"])

    def test_ogl_cooccurring_rejected(self):
        self._assert_vetoed(["OGL"])

    def test_ogl3_cooccurring_rejected(self):
        self._assert_vetoed(["OGL3"])

    def test_benign_pd_and_footer_templates_not_vetoed(self):
        # PD-* assertions and the CC-PD-Mark footer are NOT restrictive -> still accept
        res = verdict(mutate(self.base, templates=[
            "PD-RusEmpire", "PD-US-expired-text", "Cc-pd-mark-footer",
            "PD-old-text", "PD-Layout", "PD-Art", "SDC-PD-old"]))
        self.assertEqual(res["verdict"], "accept", res["reason"])

    def test_i18n_lookalike_not_false_flagged(self):
        # 'GetFallback' contains 'fal' but is not a license -> prefix match must not trip
        res = verdict(mutate(self.base, templates=[
            "PD-RusEmpire", "PD-US-expired-text", "GetFallback", "GetFallback/core"]))
        self.assertEqual(res["verdict"], "accept", res["reason"])


class B3PerFileEligibility(unittest.TestCase):
    """B3 / Option A: eligibility is decided with NO free-text Artist parsing. A file is
    admitted only on a self-contained source template, or (life-based-only) an explicit
    per-pageid curation override; otherwise rejected. P1 ships the override map empty."""
    def setUp(self):
        self.base = fx("accept_real")

    def _with_artist(self, rec, artist_value, pageid=None):
        r = copy.deepcopy(rec)
        r["imageinfo"]["extmetadata"]["Artist"] = {"value": artist_value}
        if pageid is not None:
            r["pageid"] = pageid
        return r

    def _cfg_with_override(self, pageid, facts):
        c = copy.deepcopy(CFG)
        c.setdefault("life_based_overrides", {})[str(pageid)] = facts
        return c

    def test_p1_life_based_override_allowlist_is_empty(self):
        # P1 invariant: the ONLY life-based admission route ships empty.
        self.assertEqual(CFG.get("life_based_overrides", {}), {})

    def test_life_based_only_rejected_unconditionally(self):
        # every life-based-only source template, with ANY artist text (incl. adversarial
        # co-author / HTML-metadata forms that defeated the old parser), is rejected.
        fields = ["Ivan Shishkin",
                  "Ivan Shishkin and Bad Painter",
                  "Ivan Shishkin, Li Wu",
                  "Ivan Shishkin и А.",
                  'Ivan Shishkin<div style="display:none">label QS:Len,"x"</div> and Bad Painter',
                  ""]
        for src in ("PD-old-70", "PD-old-auto", "PD-Russia", "PD-Russia-expired"):
            for field in fields:
                with self.subTest(src=src, field=field):
                    rec = self._with_artist(
                        mutate(self.base, templates=[src, "PD-US-expired"]), field)
                    res = generate.evaluate(rec, CFG)
                    self.assertEqual(res["verdict"], "reject", (src, field))
                    self.assertTrue(
                        res["reason"].startswith("life-based-requires-pageid-curation"),
                        "%s/%s -> %s" % (src, field, res["reason"]))

    def test_life_based_admitted_only_via_pageid_override(self):
        # the SOLE admission route: an explicit pageid override with eligible:true.
        rec = self._with_artist(mutate(self.base, templates=["PD-old-70", "PD-US-expired"]),
                                "Anything At All", pageid=4242)
        # without an override -> reject
        self.assertEqual(generate.evaluate(rec, CFG)["verdict"], "reject")
        # with the override -> accept, carrying the curated note
        cfg = self._cfg_with_override(4242, {"eligible": True, "death_year": 1935,
                                             "notes": "curated: d.1935, URAA via US tag"})
        res = generate.evaluate(rec, cfg)
        self.assertEqual(res["verdict"], "accept", res["reason"])
        self.assertEqual(res["entry"]["license"]["notes"], "curated: d.1935, URAA via US tag")

    def test_override_requires_eligible_true(self):
        # a present-but-not-eligible override does NOT admit (fail-closed)
        rec = self._with_artist(mutate(self.base, templates=["PD-old-70", "PD-US-expired"]),
                                "X", pageid=4242)
        for facts in ({"eligible": False}, {"eligible": "yes"}, {"death_year": 1935}, {}):
            with self.subTest(facts=facts):
                cfg = self._cfg_with_override(4242, facts)
                self.assertEqual(generate.evaluate(rec, cfg)["verdict"], "reject", facts)

    def test_self_contained_accepts_regardless_of_artist(self):
        # self-contained (publication/URAA/age>=100) PD is author-independent: accepts
        # any artist text, including a would-be "ineligible" name — the PD basis does
        # not depend on the author, so no Artist parsing is needed or done.
        for src in ("PD-RusEmpire", "PD-Russia-1996", "PD-old-100"):
            for field in ("Some Uncurated Journal", "Bad Painter",
                          "A and B and C", "Ivan Shishkin и А."):
                with self.subTest(src=src, field=field):
                    rec = self._with_artist(
                        mutate(self.base, templates=[src, "PD-US-expired"]), field)
                    res = generate.evaluate(rec, CFG)
                    self.assertEqual(res["verdict"], "accept", (src, field, res["reason"]))


class B2HarvestIntegrity(unittest.TestCase):
    """B2: partial/interrupted harvest must FAIL loudly; a shortfall must NOT clobber
    an existing good manifest. ZERO real network (generate._get is stubbed)."""
    def setUp(self):
        self._real_get = generate._get
        self._real_subcats = generate._subcats
        self._real_harvest = generate._harvest
        # neutralize sleeps so the fake run is instant
        self._real_sleep = generate.time.sleep
        generate.time.sleep = lambda *_a, **_k: None

    def tearDown(self):
        generate._get = self._real_get
        generate._subcats = self._real_subcats
        generate._harvest = self._real_harvest
        generate.time.sleep = self._real_sleep

    def _min_cfg(self):
        return {"config": {"user_agent": "t", "width_min": 3000, "thumb_width": 3000,
                           "traversal": {"max_depth": 0, "max_categories_per_seed": 5,
                                         "request_delay_s": 0}},
                "seeds": [{"key": "era:19c", "category": "Category:A"},
                          {"key": "era:20c", "category": "Category:B"}],
                "exclude_category_substrings": ["icon"], "artists": {}, "overrides": {}}

    def test_api_error_response_is_fatal(self):
        # a MediaWiki API error payload must become a fatal HarvestError, never be
        # mistaken for an empty-but-complete page (silent pagination truncation).
        import io, contextlib
        payload = io.BytesIO(json.dumps({"error": {"code": "badvalue", "info": "boom"}}).encode())

        @contextlib.contextmanager
        def fake_urlopen(req, timeout=0):
            yield payload
        orig = generate.urllib.request.urlopen
        generate.urllib.request.urlopen = fake_urlopen
        try:
            with self.assertRaises(generate.HarvestError):
                generate._get({"action": "query"}, "ua")
        finally:
            generate.urllib.request.urlopen = orig

    def test_warnings_response_is_fatal(self):
        # a module/parameter warning can signal incomplete data -> must be fatal.
        import io, contextlib
        payload = io.BytesIO(json.dumps(
            {"warnings": {"query": {"*": "formatversion ... disabled module"}},
             "query": {"pages": {}}}).encode())

        @contextlib.contextmanager
        def fake_urlopen(req, timeout=0):
            yield payload
        orig = generate.urllib.request.urlopen
        generate.urllib.request.urlopen = fake_urlopen
        try:
            with self.assertRaises(generate.HarvestError):
                generate._get({"action": "query"}, "ua")
        finally:
            generate.urllib.request.urlopen = orig

    def test_missing_batchcomplete_is_truncation_fatal(self):
        # query results present but NEITHER 'continue' NOR 'batchcomplete' -> the run
        # may be silently truncated; _harvest must raise rather than treat as complete.
        generate._get = lambda params, ua: {"query": {"pages": {}}}
        with self.assertRaises(generate.HarvestError):
            generate._harvest("Category:A", "era:19c", {}, self._min_cfg(), "ua")

    def test_partial_harvest_raises_not_swallowed(self):
        calls = {"n": 0}

        def flaky(cat, key, pages, cfg, ua):
            calls["n"] += 1
            if calls["n"] == 1:
                pages["1"] = {"pageid": 1, "title": "File:A", "lastrevid": 1,
                              "templates": [], "imageinfo": None,
                              "source_categories": {cat}, "keys": {key}, "_tplset": set()}
                return
            raise OSError("connection reset mid-pagination")
        generate._harvest = flaky
        with self.assertRaises(generate.HarvestError):
            generate.harvest_all(self._min_cfg())

    def test_shortfall_does_not_clobber_existing_manifest(self):
        import tempfile
        d = tempfile.mkdtemp()
        out = os.path.join(d, "art-manifest.json")
        good = {"schema": 2, "status": "OK", "count": 99, "entries": ["GOOD-SENTINEL"]}
        with open(out, "w") as f:
            json.dump(good, f)
        # one harvested page that passes the gate -> 1 accepted, far below FLOOR
        page = {"pageid": 7, "title": "File:X",
                "lastrevid": 1,
                "templates": [{"title": "Template:PD-RusEmpire"},
                              {"title": "Template:PD-US-expired-text"}],
                "imageinfo": copy.deepcopy(fx("accept_real")["imageinfo"]),
                "source_categories": ["Category:19th-century paintings from Russia"],
                "keys": ["era:19c"]}
        code, manifest = generate.build_and_write({"7": page}, CFG, out,
                                                  floor=50, write_evidence=False)
        self.assertEqual(code, 2)
        self.assertEqual(manifest["status"], "SHORTFALL")
        # the known-good manifest is UNTOUCHED
        with open(out) as f:
            self.assertEqual(json.load(f)["entries"], ["GOOD-SENTINEL"])
        # a clearly-labeled shortfall file exists instead
        self.assertTrue(os.path.exists(generate._shortfall_path(out)))

    def test_ok_run_writes_real_manifest(self):
        import tempfile
        d = tempfile.mkdtemp()
        out = os.path.join(d, "art-manifest.json")
        pages = {}
        base_ii = fx("accept_real")["imageinfo"]
        for i in range(50):
            pages[str(i)] = {"pageid": i, "title": "File:%d" % i, "lastrevid": 1,
                             "templates": [{"title": "Template:PD-RusEmpire"},
                                           {"title": "Template:PD-US-expired-text"}],
                             "imageinfo": copy.deepcopy(base_ii),
                             "source_categories": ["Category:19th-century paintings from Russia"],
                             "keys": ["era:19c"]}
        code, manifest = generate.build_and_write(pages, CFG, out,
                                                  floor=50, write_evidence=False)
        self.assertEqual(code, 0)
        self.assertEqual(manifest["status"], "OK")
        self.assertFalse(os.path.exists(generate._shortfall_path(out)))
        with open(out) as f:
            self.assertEqual(json.load(f)["count"], 50)


if __name__ == "__main__":
    unittest.main(verbosity=2)
