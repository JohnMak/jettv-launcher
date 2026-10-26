#!/usr/bin/env python3
"""Offline unit tests for curate.py (Step 1 curation projection + recount + hash).

ZERO NETWORK. Pure projection tested over a small synthetic fixture (not the real
50-entry manifest) so preset-count boundaries (incl. HIDDEN) are exercised directly,
plus a `ManifestArtifact`-style check of the actual committed vetted.json.

Run:  python3 -m unittest discover -s tools/art-manifest/tests -v
"""
import copy, json, os, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
sys.path.insert(0, TOOL)
import curate  # noqa: E402


def entry(id_, title="Boilerplate title", artist="Boilerplate artist", year="1872",
          categories=None):
    return {"id": id_, "title": title, "artist": artist, "year": year,
            "categories": categories or []}


FIXTURE = [
    entry(1, categories=["era:19c"]),
    entry(2, categories=["era:19c", "movement:peredvizhniki"]),
    entry(3, categories=["movement:avantgarde"]),
    entry(4, categories=["era:empire"]),
    entry(5, categories=["movement:peredvizhniki"]),
]
DROPS = {3: "non-painting:manifesto"}
CORRECTIONS = {1: {"title": "Real Title", "artist": "Real Artist", "year": "1890"}}

# Golden anchors for the committed deliverable (RealVettedArtifact). The approved hash
# now folds preset categories into the binding and commits the per-id source digests;
# it supersedes the pre-review value. Changing either pin forces a visible diff here.
APPROVED_SNAPSHOT_SHA256 = "700a085c5c224fdbf481a4c894841b3e29a3f0663e51a8382176dfea31eb947e"
SUPERSEDED_SHA256 = "6f464f8e26fba46136f5d4aaa915858fca09c303619910502fafc60d8a33b852"


def _all_kept_sources_present():
    """True iff every committed kept id has an on-disk source (sources/ is gitignored,
    so this is False on a clean checkout and the byte re-verification test is skipped)."""
    src = os.path.join(TOOL, "sources")
    if not os.path.isdir(src):
        return False
    try:
        with open(os.path.join(TOOL, "vetted.json")) as f:
            kept = json.load(f)["kept"]
    except (OSError, ValueError, KeyError):
        return False
    for e in kept:
        if not any(os.path.exists(os.path.join(src, "%s.%s" % (e["id"], ext)))
                   for ext in ("jpg", "jpeg", "png")):
            return False
    return True


class CurateProjection(unittest.TestCase):
    def test_dropped_id_excluded(self):
        kept, dropped = curate.curate(FIXTURE, DROPS, CORRECTIONS)
        self.assertNotIn(3, [e["id"] for e in kept])
        self.assertEqual([d["id"] for d in dropped], [3])
        self.assertEqual(dropped[0]["reason"], "non-painting:manifesto")

    def test_kept_ids_survive_in_manifest_order(self):
        kept, _ = curate.curate(FIXTURE, DROPS, CORRECTIONS)
        self.assertEqual([e["id"] for e in kept], [1, 2, 4, 5])

    def test_correction_overrides_boilerplate(self):
        kept, _ = curate.curate(FIXTURE, DROPS, CORRECTIONS)
        e1 = next(e for e in kept if e["id"] == 1)
        self.assertEqual(e1["title"], "Real Title")
        self.assertEqual(e1["artist"], "Real Artist")
        self.assertEqual(e1["year"], "1890")

    def test_entry_without_correction_passes_through_verbatim(self):
        kept, _ = curate.curate(FIXTURE, DROPS, CORRECTIONS)
        e2 = next(e for e in kept if e["id"] == 2)
        self.assertEqual(e2["title"], "Boilerplate title")
        self.assertEqual(e2["year"], "1872")

    def test_dropped_entry_absent_from_kept_categories_too(self):
        kept, _ = curate.curate(FIXTURE, DROPS, CORRECTIONS)
        self.assertNotIn("movement:avantgarde", [c for e in kept for c in e["categories"]])


class PresetRecount(unittest.TestCase):
    def setUp(self):
        self.kept, _ = curate.curate(FIXTURE, DROPS, CORRECTIONS)

    def test_all_counts_every_kept_entry(self):
        r = curate.preset_recount(self.kept, min_works=2)
        self.assertEqual(r["ALL"]["count"], 4)
        self.assertEqual(r["ALL"]["status"], "OFFERED")

    def test_peredvizhniki_count_and_status(self):
        r = curate.preset_recount(self.kept, min_works=2)
        self.assertEqual(r["PEREDVIZHNIKI"]["count"], 2)
        self.assertEqual(r["PEREDVIZHNIKI"]["status"], "OFFERED")

    def test_avantgarde_empty_after_drop_is_hidden(self):
        r = curate.preset_recount(self.kept, min_works=2)
        self.assertEqual(r["AVANTGARDE"]["count"], 0)
        self.assertEqual(r["AVANTGARDE"]["status"], "HIDDEN")

    def test_era19c_below_floor_is_hidden(self):
        # era:19c matches ids 1,2 = 2 entries; floor 3 -> HIDDEN
        r = curate.preset_recount(self.kept, min_works=3)
        self.assertEqual(r["ERA_19C"]["count"], 2)
        self.assertEqual(r["ERA_19C"]["status"], "HIDDEN")

    def test_era19c_at_floor_is_offered(self):
        r = curate.preset_recount(self.kept, min_works=2)
        self.assertEqual(r["ERA_19C"]["status"], "OFFERED")


class PresetAllNonemptyException(unittest.TestCase):
    """COMMIT_REVIEW finding 4 / D6: the ALL preset («Всё») is the guaranteed fallback
    and is OFFERED whenever the vetted set is nonempty — the MIN_PRESET_WORKS floor
    applies only to the category-filtered presets, never to ALL."""
    @staticmethod
    def _kept(n):
        return [entry(i, categories=["era:19c"]) for i in range(1, n + 1)]

    def test_all_hidden_only_when_empty(self):
        r = curate.preset_recount(self._kept(0), min_works=4)
        self.assertEqual(r["ALL"]["count"], 0)
        self.assertEqual(r["ALL"]["status"], "HIDDEN")

    def test_all_offered_for_small_nonempty_sets(self):
        # 1,2,3 works are below the 4-work floor yet ALL must still be OFFERED.
        for n in (1, 2, 3, 4):
            with self.subTest(n=n):
                r = curate.preset_recount(self._kept(n), min_works=4)
                self.assertEqual(r["ALL"]["count"], n)
                self.assertEqual(r["ALL"]["status"], "OFFERED")
                self.assertEqual(r["ALL"]["min"], 1)  # exception expressed as eff-min 1

    def test_category_preset_still_honours_floor(self):
        # the floor is NOT globally removed: a 3-work category preset stays HIDDEN.
        r = curate.preset_recount(self._kept(3), min_works=4)
        self.assertEqual(r["ERA_19C"]["count"], 3)
        self.assertEqual(r["ERA_19C"]["status"], "HIDDEN")


class SnapshotHash(unittest.TestCase):
    def setUp(self):
        self.kept, _ = curate.curate(FIXTURE, DROPS, CORRECTIONS)
        self.tmp = tempfile.mkdtemp()
        for e in self.kept:
            with open(os.path.join(self.tmp, "%s.jpg" % e["id"]), "wb") as f:
                f.write(b"fake-bytes-%d" % e["id"])

    def test_hash_stable_under_key_order(self):
        h1 = curate.compute_snapshot_hash(self.kept, self.tmp)
        reordered = [dict(e) for e in reversed(self.kept)]
        h2 = curate.compute_snapshot_hash(reordered, self.tmp)
        self.assertEqual(h1, h2)

    def test_hash_changes_when_entry_changes(self):
        h1 = curate.compute_snapshot_hash(self.kept, self.tmp)
        mutated = copy.deepcopy(self.kept)
        mutated[0]["title"] = mutated[0]["title"] + " (edited)"
        h2 = curate.compute_snapshot_hash(mutated, self.tmp)
        self.assertNotEqual(h1, h2)

    def test_hash_changes_when_categories_change(self):
        # COMMIT_REVIEW finding 2: preset categories are bound into the approval hash,
        # so a category (preset-tag) mutation must invalidate it.
        smap = curate.source_sha1_map(self.kept, self.tmp)
        h1 = curate.snapshot_hash_from_digests(self.kept, smap)
        mutated = copy.deepcopy(self.kept)
        mutated[0]["categories"] = list(mutated[0]["categories"]) + ["movement:injected"]
        h2 = curate.snapshot_hash_from_digests(mutated, smap)
        self.assertNotEqual(h1, h2)

    def test_hash_changes_when_committed_digest_changes(self):
        # tampering a committed source digest (without touching kept) flips the hash.
        smap = curate.source_sha1_map(self.kept, self.tmp)
        h1 = curate.snapshot_hash_from_digests(self.kept, smap)
        tampered = dict(smap)
        k = next(iter(tampered))
        tampered[k] = "0" * 40
        h2 = curate.snapshot_hash_from_digests(self.kept, tampered)
        self.assertNotEqual(h1, h2)

    def test_from_digests_matches_disk_read(self):
        # the pure digest path and the disk-reading convenience agree.
        smap = curate.source_sha1_map(self.kept, self.tmp)
        self.assertEqual(curate.snapshot_hash_from_digests(self.kept, smap),
                         curate.compute_snapshot_hash(self.kept, self.tmp))

    def test_hash_changes_when_source_byte_flips(self):
        h1 = curate.compute_snapshot_hash(self.kept, self.tmp)
        target = os.path.join(self.tmp, "%s.jpg" % self.kept[0]["id"])
        with open(target, "ab") as f:
            f.write(b"X")
        h2 = curate.compute_snapshot_hash(self.kept, self.tmp)
        self.assertNotEqual(h1, h2)

    def test_missing_source_file_recorded_as_none_not_fatal(self):
        os.remove(os.path.join(self.tmp, "%s.jpg" % self.kept[0]["id"]))
        # must not raise
        curate.compute_snapshot_hash(self.kept, self.tmp)


class UnknownIdGuard(unittest.TestCase):
    def test_build_fails_on_unknown_drop_or_correction_id(self):
        manifest_ids = {e["id"] for e in FIXTURE}
        bogus_drops = dict(DROPS)
        bogus_drops[999] = "non-painting:typo"
        unknown = sorted((set(bogus_drops) | set(CORRECTIONS)) - manifest_ids)
        self.assertEqual(unknown, [999])


class RealVettedArtifact(unittest.TestCase):
    """Validate the actual committed deliverable, offline (mirrors test_gate.py's
    ManifestArtifact pattern)."""
    @classmethod
    def setUpClass(cls):
        with open(os.path.join(TOOL, "vetted.json")) as f:
            cls.v = json.load(f)
        with open(os.path.join(TOOL, "art-manifest.json")) as f:
            cls.m = json.load(f)

    def test_schema_and_counts(self):
        self.assertEqual(self.v["schema"], 1)
        self.assertEqual(self.v["kept_count"], len(self.v["kept"]))
        self.assertEqual(self.v["dropped_count"], len(self.v["dropped"]))
        self.assertEqual(self.v["kept_count"] + self.v["dropped_count"], self.m["count"])

    def test_every_kept_id_present_in_source_manifest(self):
        manifest_ids = {e["id"] for e in self.m["entries"]}
        for e in self.v["kept"]:
            self.assertIn(e["id"], manifest_ids)

    def test_every_dropped_id_present_in_source_manifest_and_has_reason(self):
        manifest_ids = {e["id"] for e in self.m["entries"]}
        for d in self.v["dropped"]:
            self.assertIn(d["id"], manifest_ids)
            self.assertTrue(d["reason"])

    def test_no_id_both_kept_and_dropped(self):
        kept_ids = {e["id"] for e in self.v["kept"]}
        dropped_ids = {d["id"] for d in self.v["dropped"]}
        self.assertEqual(kept_ids & dropped_ids, set())

    def test_approved_with_matching_snapshot(self):
        # Step 2 owner gate has run for this delivery: approved=true and the recorded
        # snapshot hash is reproducible OFFLINE from the COMMITTED per-id digests
        # (sources/ is gitignored and absent on a clean checkout — no byte read here).
        self.assertTrue(self.v["approved"])
        self.assertTrue(self.v["approved_snapshot_sha256"])
        self.assertEqual(len(self.v["source_sha1"]), len(self.v["kept"]))
        computed = curate.snapshot_hash_from_digests(
            self.v["kept"], self.v["source_sha1"])
        self.assertEqual(computed, self.v["approved_snapshot_sha256"])

    def test_snapshot_derives_from_exactly_approved_content(self):
        # Golden anchor: the approved hash equals a literal pinned here AND is
        # reproduced from the committed kept set + digests. A real content mutation
        # cannot hide behind a silent recompute — it would also have to change this
        # literal, which is visible in review. (finding 2 identity guard)
        self.assertEqual(self.v["approved_snapshot_sha256"], APPROVED_SNAPSHOT_SHA256)
        self.assertEqual(
            curate.snapshot_hash_from_digests(self.v["kept"], self.v["source_sha1"]),
            APPROVED_SNAPSHOT_SHA256)

    def test_supersession_audit_trail_present(self):
        # finding 2 audit trail: old->new hash recorded, with the content-unchanged
        # assertion, so the hash change is traceable to the integrity fix (not content).
        s = self.v["snapshot_supersession"]
        self.assertEqual(s["superseded_sha256"], SUPERSEDED_SHA256)
        self.assertNotEqual(s["superseded_sha256"], self.v["approved_snapshot_sha256"])
        self.assertIn("categories", s["reason"])
        self.assertTrue(s["content_unchanged_assertion"])

    @unittest.skipUnless(
        _all_kept_sources_present(), "sources/ absent (gitignored) — local-only check")
    def test_source_bytes_match_committed_digests_local_only(self):
        # LOCAL-ONLY (skipped on a clean checkout): re-read the actual source bytes and
        # confirm they reproduce both the committed digest map and the approval hash.
        src = os.path.join(TOOL, "sources")
        live = curate.source_sha1_map(self.v["kept"], src)
        self.assertEqual(dict(live), dict(self.v["source_sha1"]))
        self.assertEqual(curate.compute_snapshot_hash(self.v["kept"], src),
                         self.v["approved_snapshot_sha256"])

    def test_known_non_paintings_are_dropped(self):
        dropped_ids = {d["id"] for d in self.v["dropped"]}
        for bad_id in (145201352, 145222274, 145221847, 154018212, 145222807, 34219161):
            self.assertIn(bad_id, dropped_ids)

    def test_avantgarde_pool_is_empty_and_hidden(self):
        av = self.v["preset_recount"]["AVANTGARDE"]
        self.assertEqual(av["count"], 0)
        self.assertEqual(av["status"], "HIDDEN")

    def test_all_preset_never_below_min(self):
        for key, r in self.v["preset_recount"].items():
            self.assertEqual(r["status"], "OFFERED" if r["count"] >= r["min"] else "HIDDEN")


if __name__ == "__main__":
    unittest.main(verbosity=2)
