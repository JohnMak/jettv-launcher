#!/usr/bin/env python3
"""Offline unit tests for publish.py (Step 3 immutable publish).

ZERO NETWORK, ZERO SERVER. Synthetic in-memory JPEGs + a LocalTransport (local dir) +
validate_published.DirFetcher stand in for the Caddy VPS. Covers: downscale clamp,
content-addressed naming, slim-manifest shape (artist omitted), the fail-closed approval
gate, the terminal source-fetch guard, caps, and the append-only / retention invariant
(aged manifest A -> later publish B -> attempted cleanup, inventory diff + A still resolves).

Run:  python3 -m unittest discover -s tools/art-manifest/tests -v
"""
import io, json, os, re, sys, tempfile, unittest
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
sys.path.insert(0, TOOL)
import curate            # noqa: E402
import publish           # noqa: E402
import validate_published as vp  # noqa: E402

from PIL import Image    # noqa: E402


def make_jpeg(w, h, color=(123, 80, 40)):
    im = Image.new("RGB", (w, h), color)
    buf = io.BytesIO()
    im.save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def write_sources(sources_dir, specs):
    """specs: {id: (w, h, color)} -> write <id>.jpg; return the bytes map."""
    os.makedirs(sources_dir, exist_ok=True)
    out = {}
    for eid, (w, h, color) in specs.items():
        data = make_jpeg(w, h, color)
        with open(os.path.join(sources_dir, "%s.jpg" % eid), "wb") as f:
            f.write(data)
        out[eid] = data
    return out


def make_vetted(kept, sources_dir):
    """Build a synthetic, self-consistent 'approved' vetted dict over sources_dir."""
    source_sha1 = curate.source_sha1_map(kept, sources_dir)
    stored = curate.snapshot_hash_from_digests(kept, source_sha1)
    return OrderedDict([
        ("schema", 1),
        ("approved", True),
        ("approved_snapshot_sha256", stored),
        ("kept_count", len(kept)),
        ("source_sha1", source_sha1),
        ("kept", kept),
    ]), stored


def kept_entry(eid, title="T", artist="A", year="1891", categories=None):
    return {"id": eid, "title": title, "artist": artist, "year": year,
            "categories": categories or ["era:19c"]}


class PureHelpers(unittest.TestCase):
    def test_downscale_clamps_long_edge(self):
        out = publish.downscale_jpeg(make_jpeg(3000, 2000), max_edge=1920, quality=85)
        w, h = vp.image_dimensions(out)
        self.assertEqual(max(w, h), 1920)
        self.assertEqual((w, h), (1920, 1280))

    def test_downscale_never_upscales(self):
        out = publish.downscale_jpeg(make_jpeg(500, 400), max_edge=1920)
        self.assertEqual(vp.image_dimensions(out), (500, 400))

    def test_downscale_rejects_non_image(self):
        with self.assertRaises(publish.PublishError):
            publish.downscale_jpeg(b"not an image")

    def test_derivative_filename_shape(self):
        name = publish.derivative_filename(56420704, "a" * 40)
        self.assertEqual(name, "56420704-aaaaaaaaaaaa.jpg")
        self.assertTrue(vp.FILE_RE.match(name))
        self.assertTrue(vp.file_name_matches_sha1(name, "a" * 40))

    def test_build_entry_omits_artist(self):
        e = publish.build_entry(kept_entry(7, artist="Should Not Appear"), "7-abc.jpg", "b" * 40)
        self.assertEqual(list(e.keys()), ["id", "title", "year", "categories", "file", "sha1"])
        self.assertNotIn("artist", e)

    def test_build_manifest_shape(self):
        m = publish.build_manifest([], "2026-10-26T00:00:00Z")
        self.assertEqual(m["base_url"], "https://art.jmwork.ru/")
        self.assertEqual(m["generated_at"], "2026-10-26T00:00:00Z")
        self.assertEqual(m["entries"], [])


class ApprovalGate(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "sources")
        write_sources(self.src, {1: (100, 80, (10, 20, 30)), 2: (90, 70, (40, 50, 60))})
        self.kept = [kept_entry(1), kept_entry(2)]
        self.vetted, self.stored = make_vetted(self.kept, self.src)
        self.prefix = self.stored[:8]

    def test_verify_approval_passes_for_consistent_set(self):
        self.assertEqual(publish.verify_approval(self.vetted, self.src, self.prefix), self.stored)

    def test_rejects_unapproved_flag(self):
        self.vetted["approved"] = False
        with self.assertRaises(publish.ApprovalMismatch):
            publish.verify_approval(self.vetted, self.src, self.prefix)

    def test_rejects_non_bool_truthy_approved(self):
        # P2 strict-boolean: only JSON `true` passes. Truthy non-booleans (the string
        # "false", "true", 1, "yes") that otherwise match MUST be rejected.
        for bad in ("false", "true", 1, "yes", [1]):
            self.vetted["approved"] = bad
            with self.assertRaises(publish.ApprovalMismatch):
                publish.verify_approval(self.vetted, self.src, self.prefix)
        # sanity: the genuine boolean still passes
        self.vetted["approved"] = True
        self.assertEqual(publish.verify_approval(self.vetted, self.src, self.prefix), self.stored)

    def test_rejects_wrong_prefix(self):
        with self.assertRaises(publish.ApprovalMismatch):
            publish.verify_approval(self.vetted, self.src, "deadbeef")

    def test_rejects_digest_drift(self):
        # tamper a committed digest without touching stored hash -> mismatch
        k = next(iter(self.vetted["source_sha1"]))
        self.vetted["source_sha1"][k] = "0" * 40
        with self.assertRaises(publish.ApprovalMismatch):
            publish.verify_approval(self.vetted, self.src, self.prefix)

    def test_rejects_live_byte_drift(self):
        # mutate a source file on disk -> live bytes no longer reproduce the stored hash
        with open(os.path.join(self.src, "1.jpg"), "ab") as f:
            f.write(b"X")
        with self.assertRaises(publish.ApprovalMismatch):
            publish.verify_approval(self.vetted, self.src, self.prefix)

    def test_real_vetted_matches_default_prefix(self):
        # the committed deliverable is the owner-approved 700a085c set.
        with open(os.path.join(TOOL, "vetted.json")) as f:
            v = json.load(f)
        self.assertTrue(v["approved_snapshot_sha256"].startswith(publish.APPROVED_HASH_PREFIX))


class SourceFetchGuard(unittest.TestCase):
    def test_missing_source_raises_fetch_required(self):
        tmp = tempfile.mkdtemp()
        src = os.path.join(tmp, "sources")
        write_sources(src, {1: (100, 80, (1, 2, 3))})
        kept = [kept_entry(1), kept_entry(2)]  # id 2 has no cached source
        with self.assertRaises(publish.SourceFetchRequired):
            publish.resolve_sources(kept, src)


class PublishPipeline(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "sources")
        write_sources(self.src, {
            101: (3000, 2000, (200, 50, 50)),
            102: (800, 600, (50, 200, 50)),
            103: (1920, 1080, (50, 50, 200)),
        })
        self.kept = [kept_entry(101), kept_entry(102, categories=["movement:peredvizhniki"]),
                     kept_entry(103)]
        self.vetted, self.stored = make_vetted(self.kept, self.src)
        self.prefix = self.stored[:8]
        self.web = os.path.join(self.tmp, "webroot")

    def _publish(self, promote=True):
        t = publish.LocalTransport(self.web)
        return publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                               promote=promote, approved_prefix=self.prefix)

    def test_publish_promotes_and_validates(self):
        res = self._publish(promote=True)
        self.assertEqual(res["entries"], 3)
        self.assertEqual(res["images_uploaded"], 3)
        self.assertTrue(res["promoted"])
        # live manifest present; validate the whole published set against the local webroot
        ok, report = vp.validate(vp.BASE_URL + "manifest.json", vp.DirFetcher(self.web))
        self.assertTrue(ok, report)

    def test_images_are_content_addressed_and_downscaled(self):
        self._publish()
        imgs = os.listdir(os.path.join(self.web, "images"))
        self.assertEqual(len(imgs), 3)
        for name in imgs:
            self.assertTrue(vp.FILE_RE.match(name))
            with open(os.path.join(self.web, "images", name), "rb") as f:
                data = f.read()
            w, h = vp.image_dimensions(data)
            self.assertLessEqual(max(w, h), 1920)
            self.assertTrue(vp.file_name_matches_sha1(name, vp.sha1_hex(data)))

    def test_manifest_entry_schema(self):
        self._publish()
        with open(os.path.join(self.web, "manifest.json")) as f:
            m = json.load(f)
        self.assertEqual(m["base_url"], "https://art.jmwork.ru/")
        for e in m["entries"]:
            self.assertEqual(set(e.keys()), {"id", "title", "year", "categories", "file", "sha1"})

    def test_no_promote_leaves_no_live_manifest(self):
        res = self._publish(promote=False)
        self.assertFalse(res["promoted"])
        # P1: staged manifest lives under a per-content unique name (manifest.json.staged.<h>),
        # NOT the fixed "manifest.json.staged"; exactly one such staged file, no live manifest.
        staged = [f for f in os.listdir(self.web) if f.startswith(publish.STAGED_PREFIX)]
        self.assertEqual(len(staged), 1, staged)
        self.assertFalse(os.path.exists(os.path.join(self.web, "manifest.json")))

    def test_staged_validation_failure_aborts_promote(self):
        # a validator that always fails must prevent promotion (live stays untouched)
        t = publish.LocalTransport(self.web)
        with self.assertRaises(publish.PublishError):
            publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                            validate_staged=lambda staged_name: (False, "forced failure", None),
                            promote=True, approved_prefix=self.prefix)
        self.assertFalse(os.path.exists(os.path.join(self.web, "manifest.json")))

    def test_republish_is_idempotent_append_only(self):
        self._publish()
        before = set(os.listdir(os.path.join(self.web, "images")))
        res2 = self._publish()  # same content -> all skipped, nothing overwritten
        after = set(os.listdir(os.path.join(self.web, "images")))
        self.assertEqual(res2["images_uploaded"], 0)
        self.assertEqual(res2["images_skipped"], 3)
        self.assertEqual(before, after)


class RetentionAppendOnly(unittest.TestCase):
    """H1 (Rev5) widened retention: aged manifest A -> later publish B (new content) ->
    attempted cleanup. The published set must be append-only and every A-referenced image
    must keep resolving with a sha1 match."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "sources")
        self.web = os.path.join(self.tmp, "webroot")
        write_sources(self.src, {201: (800, 600, (10, 10, 10)), 202: (700, 500, (20, 20, 20))})

    def _publish(self, kept):
        vetted, stored = make_vetted(kept, self.src)
        t = publish.LocalTransport(self.web)
        return publish.publish(vetted, self.src, t, "2026-10-26T00:00:00Z",
                               promote=True, approved_prefix=stored[:8])

    def test_publish_B_retains_A_images_and_grows_set(self):
        # Publish A (ids 201,202)
        self._publish([kept_entry(201), kept_entry(202)])
        with open(os.path.join(self.web, "manifest.json")) as f:
            manifest_a = json.load(f)
        a_files = {e["file"] for e in manifest_a["entries"]}
        inv_after_a = set(os.listdir(os.path.join(self.web, "images")))

        # Publish B: re-encode id 201 (different bytes -> NEW immutable name) + add id 203.
        write_sources(self.src, {201: (810, 600, (11, 11, 11)), 203: (640, 480, (30, 30, 30))})
        self._publish([kept_entry(201), kept_entry(202), kept_entry(203)])
        inv_after_b = set(os.listdir(os.path.join(self.web, "images")))

        # append-only: B's inventory is a superset of A's; nothing pruned.
        self.assertEqual(vp.assert_append_only(inv_after_a, inv_after_b), [])
        self.assertTrue(inv_after_a.issubset(inv_after_b))
        self.assertGreater(len(inv_after_b), len(inv_after_a))

        # "attempted cleanup": there is NO prune/delete path in the pipeline.
        self.assertFalse(hasattr(publish, "prune"))
        self.assertFalse(hasattr(publish, "delete"))
        # re-running the full post-publish lifecycle changes nothing (idempotent).
        self._publish([kept_entry(201), kept_entry(202), kept_entry(203)])
        self.assertTrue(inv_after_b.issubset(set(os.listdir(os.path.join(self.web, "images")))))

        # every A-referenced image still fetches 200 + sha1-matches against the live server,
        # even though the live manifest now points at B.
        fetcher = vp.DirFetcher(self.web)
        for e in manifest_a["entries"]:
            status, data = fetcher.fetch(vp.BASE_URL + "images/" + e["file"])
            self.assertEqual(status, 200, e["file"])
            self.assertEqual(vp.sha1_hex(data), e["sha1"])
        self.assertTrue(a_files.issubset(inv_after_b))

    def test_assert_append_only_flags_deletion(self):
        self.assertTrue(vp.assert_append_only({"a", "b"}, {"a"}))
        self.assertEqual(vp.assert_append_only({"a"}, {"a", "b"}), [])


class ExactBufferApproval(unittest.TestCase):
    """COMMIT_REVIEW P2 (source-approval race): the exact buffer handed to the encoder must
    be verified against the committed approved digest, so a source swapped between the gate's
    read and the encoder's read cannot publish unapproved bytes."""

    def test_matching_buffer_passes(self):
        raw = b"approved source bytes"
        src_map = {"7": vp.sha1_hex(raw)}
        publish.verify_source_bytes(raw, 7, src_map)  # no raise

    def test_mutated_buffer_rejected(self):
        raw = b"approved source bytes"
        src_map = {"7": vp.sha1_hex(raw)}
        with self.assertRaises(publish.ApprovalMismatch):
            publish.verify_source_bytes(b"TAMPERED bytes", 7, src_map)

    def test_missing_committed_digest_rejected(self):
        with self.assertRaises(publish.ApprovalMismatch):
            publish.verify_source_bytes(b"x", 7, {})  # no committed digest for id -> reject

    def test_build_payload_rejects_source_swapped_after_gate(self):
        # End-to-end: verify_approval passes, then a source file is swapped to DIFFERENT bytes
        # before build_payload's encoder read. The per-buffer check must abort with
        # ApprovalMismatch (not silently publish the swapped bytes).
        tmp = tempfile.mkdtemp()
        src = os.path.join(tmp, "sources")
        write_sources(src, {1: (100, 80, (10, 20, 30))})
        kept = [kept_entry(1)]
        vetted, stored = make_vetted(kept, src)

        orig_verify = publish.verify_approval

        def swap_then_verify(v, s, prefix):
            result = orig_verify(v, s, prefix)      # gate reads the pristine source -> passes
            write_sources(src, {1: (100, 80, (99, 99, 99))})  # race: swap bytes post-gate
            return result

        try:
            publish.verify_approval = swap_then_verify
            with self.assertRaises(publish.ApprovalMismatch):
                publish.build_payload(vetted, src, "2026-10-26T00:00:00Z", stored[:8])
        finally:
            publish.verify_approval = orig_verify


class MissingSourceOrdering(unittest.TestCase):
    """COMMIT_REVIEW P3 (safety-critical): a genuinely missing approved source must raise the
    TERMINAL SourceFetchRequired (STOP + escalate), NOT be masked by ApprovalMismatch. Presence
    is checked before approval byte-verification inside build_payload."""

    def test_missing_source_raises_fetch_required_not_mismatch(self):
        tmp = tempfile.mkdtemp()
        src = os.path.join(tmp, "sources")
        write_sources(src, {1: (100, 80, (1, 2, 3))})  # id 1 present, id 2 absent
        kept = [kept_entry(1), kept_entry(2)]
        vetted, stored = make_vetted(kept, src)  # committed digest for id 2 is None
        with self.assertRaises(publish.SourceFetchRequired):
            publish.build_payload(vetted, src, "2026-10-26T00:00:00Z", stored[:8])


class UnvalidatedBytePromotion(unittest.TestCase):
    """COMMIT_REVIEW P1: promotion must only ever promote the EXACT bytes that passed staged
    validation. Two bindings enforce this: (1) publish compares the bytes the validator
    actually FETCHED (validate_staged's 3rd return value) against the staged manifest it would
    promote — a redirect/swap that validated a DIFFERENT manifest aborts; (2) promote re-reads
    the staged file and refuses to rename anything but the validated bytes (local-change
    defense). Staged names are also per-content unique."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "sources")
        write_sources(self.src, {1: (120, 90, (5, 6, 7))})
        self.kept = [kept_entry(1)]
        self.vetted, self.stored = make_vetted(self.kept, self.src)
        self.web = os.path.join(self.tmp, "webroot")

    def test_staged_name_is_content_unique(self):
        self.assertTrue(publish.staged_name_for(b"A").startswith(publish.STAGED_PREFIX))
        self.assertNotEqual(publish.staged_name_for(b"A"), publish.staged_name_for(b"B"))
        self.assertEqual(publish.staged_name_for(b"A"), publish.staged_name_for(b"A"))

    def test_promote_refuses_drifted_staged_bytes(self):
        # Simulate a writer replacing the staged bytes at/after validation: a validator that
        # overwrites the staged file with UNVALIDATED JSON and reports it as what it validated.
        # Both the fetched-bytes binding (tampered != staged mbytes) and promote's on-disk
        # re-read refuse the drift; the live manifest is never written.
        t = publish.LocalTransport(self.web)

        def tamper_then_pass(staged_name):
            tampered = b'{"unvalidated":"bytes"}'
            with open(os.path.join(self.web, staged_name), "wb") as f:
                f.write(tampered)  # not what we staged
            return True, "ok (but bytes were swapped)", tampered

        with self.assertRaises(publish.PublishError):
            publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                            validate_staged=tamper_then_pass, promote=True,
                            approved_prefix=self.stored[:8])
        self.assertFalse(os.path.exists(os.path.join(self.web, "manifest.json")))

    def test_promote_refuses_when_validation_saw_different_bytes(self):
        # P1 ROOT (the reproduced redirect-to-old-live scenario): the validator reports PASS but
        # the bytes it actually FETCHED+validated are a DIFFERENT, independently-valid manifest
        # than the staged file we would promote. publish must bind promotion to the validated
        # bytes and abort — otherwise it would install a manifest that was never validated.
        t = publish.LocalTransport(self.web)
        other_valid = publish.manifest_bytes(
            publish.build_manifest([], "2026-10-26T00:00:00Z"))  # a valid, but DIFFERENT manifest

        def pass_on_other_bytes(staged_name):
            # staged file on disk is the real mbytes; validator claims success but returns the
            # bytes a redirect served instead (the old live manifest).
            return True, "validated (redirect served different bytes)", other_valid

        with self.assertRaises(publish.PublishError):
            publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                            validate_staged=pass_on_other_bytes, promote=True,
                            approved_prefix=self.stored[:8])
        self.assertFalse(os.path.exists(os.path.join(self.web, "manifest.json")))

    def test_promote_binds_when_validator_returns_staged_bytes(self):
        # The honest path: a validator that fetches and returns the exact staged bytes passes the
        # binding and promotes normally (guards against the binding being over-strict).
        t = publish.LocalTransport(self.web)

        def honest_validate(staged_name):
            with open(os.path.join(self.web, staged_name), "rb") as f:
                return True, "ok", f.read()

        res = publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                              validate_staged=honest_validate, promote=True,
                              approved_prefix=self.stored[:8])
        self.assertTrue(res["promoted"])
        self.assertTrue(os.path.exists(os.path.join(self.web, "manifest.json")))

    def test_promote_lands_exact_validated_bytes(self):
        t = publish.LocalTransport(self.web)
        res = publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                              promote=True, approved_prefix=self.stored[:8])
        self.assertTrue(res["promoted"])
        with open(os.path.join(self.web, "manifest.json"), "rb") as f:
            live = f.read()
        # the promoted bytes are exactly the manifest bytes whose sha1 named the staged file
        self.assertTrue(res["staged_url"].rsplit("/", 1)[1]
                        == publish.staged_name_for(live))


class InterruptedUploadRetry(unittest.TestCase):
    """COMMIT_REVIEW P2 (interrupted uploads break retry semantics): a leftover `.part` from an
    interrupted upload must be excluded from the retention inventory, so a retry neither
    false-trips the append-only invariant nor blocks promotion."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.src = os.path.join(self.tmp, "sources")
        write_sources(self.src, {1: (400, 300, (9, 9, 9)), 2: (300, 200, (8, 8, 8))})
        self.kept = [kept_entry(1), kept_entry(2)]
        self.vetted, self.stored = make_vetted(self.kept, self.src)
        self.web = os.path.join(self.tmp, "webroot")

    def test_part_excluded_from_inventory(self):
        t = publish.LocalTransport(self.web)
        # drop a leftover partial into the images dir (an interrupted prior upload)
        with open(os.path.join(t.images_dir, "1-deadbeefdead.jpg.part"), "wb") as f:
            f.write(b"interrupted")
        self.assertEqual(t.existing_images(), set())  # .part not counted

    def test_retry_with_leftover_part_promotes_without_violation(self):
        t = publish.LocalTransport(self.web)
        with open(os.path.join(t.images_dir, "1-deadbeefdead.jpg.part"), "wb") as f:
            f.write(b"interrupted upload")
        # must NOT raise APPEND-ONLY VIOLATION and MUST promote the live manifest
        res = publish.publish(self.vetted, self.src, t, "2026-10-26T00:00:00Z",
                              promote=True, approved_prefix=self.stored[:8])
        self.assertTrue(res["promoted"])
        self.assertTrue(os.path.exists(os.path.join(self.web, "manifest.json")))
        # the stale .part is never promoted to a published image
        published = {f for f in os.listdir(t.images_dir) if not f.endswith(".part")}
        self.assertNotIn("1-deadbeefdead.jpg", published)


class _CapturingSsh(publish.SshTransport):
    """SshTransport whose _run records the composed remote command instead of shelling out.
    `existing_images()` sees an empty server; every other command 'succeeds'."""

    def __init__(self, *a, **k):
        self.calls = []
        super(_CapturingSsh, self).__init__(*a, **k)

    def _run(self, remote_argv, input_bytes=None):
        self.calls.append(" ".join(remote_argv))
        return b""  # ls -> no existing images; writes/promotes -> success, empty stdout


class OverwriteRaceSafety(unittest.TestCase):
    """COMMIT_REVIEW P2: image installs must use a per-upload UNIQUE temp (no shared-path
    truncation) and an atomic install that REFUSES to overwrite an existing destination (no
    mv -f clobber). Covers both transports."""

    def test_unique_temp_distinct_and_filtered(self):
        a = publish.unique_temp("/srv/images/x.jpg")
        b = publish.unique_temp("/srv/images/x.jpg")
        self.assertNotEqual(a, b)                          # concurrent writers never share a path
        self.assertTrue(a.endswith(publish.TEMP_SUFFIX))   # excluded from the retention inventory
        self.assertTrue(b.endswith(publish.TEMP_SUFFIX))

    def test_local_atomic_install_never_overwrites_existing(self):
        # A destination that appears AFTER put_image's existence check must NOT be clobbered.
        tmp = tempfile.mkdtemp()
        t = publish.LocalTransport(os.path.join(tmp, "webroot"))
        dest = os.path.join(t.images_dir, "9-abcdefabcdef.jpg")
        with open(dest, "wb") as f:
            f.write(b"ORIGINAL CONTENT")
        created = t._atomic_install(dest, b"DIFFERENT CONTENT")  # race: dest already there
        self.assertFalse(created)                               # refused, did not create
        with open(dest, "rb") as f:
            self.assertEqual(f.read(), b"ORIGINAL CONTENT")      # never overwritten/corrupted
        # no leftover temp files from the refused install
        leftovers = [x for x in os.listdir(t.images_dir) if x.endswith(publish.TEMP_SUFFIX)]
        self.assertEqual(leftovers, [])

    def test_local_concurrent_identical_upload_idempotent(self):
        # Two uploads of the same content-addressed image: first creates, second is a no-op; the
        # bytes are intact and no temp is left behind.
        tmp = tempfile.mkdtemp()
        t = publish.LocalTransport(os.path.join(tmp, "webroot"))
        dest = os.path.join(t.images_dir, "5-0123456789ab.jpg")
        self.assertTrue(t._atomic_install(dest, b"payload"))   # first lands
        self.assertFalse(t._atomic_install(dest, b"payload"))  # second refuses (exists)
        with open(dest, "rb") as f:
            self.assertEqual(f.read(), b"payload")
        self.assertEqual([x for x in os.listdir(t.images_dir)
                          if x.endswith(publish.TEMP_SUFFIX)], [])

    def test_ssh_put_image_uses_no_clobber_unique_temp(self):
        t = _CapturingSsh("host", "user", "/key", "/var/www/art")
        t.put_image("1-aaaaaaaaaaaa.jpg", b"img-bytes")
        install = [c for c in t.calls if "images/1-aaaaaaaaaaaa.jpg" in c][-1]
        self.assertIn("ln ", install)              # atomic no-clobber hard link
        self.assertIn("2>/dev/null", install)      # a pre-existing dest is tolerated, not clobbered
        self.assertNotIn("mv -f", install)         # the clobbering rename is gone
        # the temp is a per-upload UNIQUE name (<remote>.<pid>.<rand>.part), not the shared .part
        self.assertTrue(re.search(r"images/1-aaaaaaaaaaaa\.jpg\.\d+\.[0-9a-f]+\.part", install),
                        install)
        self.assertNotIn("images/1-aaaaaaaaaaaa.jpg.part'", install)  # not the old shared temp


if __name__ == "__main__":
    unittest.main(verbosity=2)
