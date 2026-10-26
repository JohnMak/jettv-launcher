#!/usr/bin/env python3
"""Offline unit tests for contact_sheet.py's pure rendering helpers.

ZERO NETWORK, no real images (make_thumb_data_uri is exercised separately against a
tiny in-memory PNG fixture written to a temp dir, skipped if PIL is unavailable — the
module already degrades to a text-only card when PIL/decoding fails, so the skip is
safe). The snapshot-hash binding itself is covered by test_curate.py; the sha256
helper curate.compute_snapshot_hash is what contact_sheet.py reports verbatim.

Run:  python3 -m unittest discover -s tools/art-manifest/tests -v
"""
import os, sys, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.dirname(HERE)
sys.path.insert(0, TOOL)
import contact_sheet  # noqa: E402


class Escaping(unittest.TestCase):
    def test_html_special_chars_escaped(self):
        out = contact_sheet._esc('<b>"Tom" & Jerry</b>')
        self.assertNotIn("<b>", out)
        self.assertIn("&lt;b&gt;", out)
        self.assertIn("&amp;", out)
        self.assertIn("&quot;", out)

    def test_non_string_input_stringified(self):
        self.assertEqual(contact_sheet._esc(145201352), "145201352")


class TagChips(unittest.TestCase):
    def test_known_tag_gets_russian_label(self):
        html = contact_sheet._tag_chips(["movement:peredvizhniki"])
        self.assertIn("Передвижники", html)
        self.assertIn('class="chip"', html)

    def test_unknown_tag_falls_back_to_raw_key(self):
        html = contact_sheet._tag_chips(["artist:unknown-painter"])
        self.assertIn("artist:unknown-painter", html)

    def test_empty_categories_renders_no_chips(self):
        self.assertEqual(contact_sheet._tag_chips([]), "")

    def test_no_inline_style_attribute(self):
        html = contact_sheet._tag_chips(["era:19c", "movement:avantgarde"])
        self.assertNotIn("style=", html)


class RecountTable(unittest.TestCase):
    def test_offered_and_hidden_rows_rendered(self):
        recount = {
            "ALL": {"label": "Всё", "count": 19, "min": 4, "status": "OFFERED"},
            "AVANTGARDE": {"label": "Авангард", "count": 0, "min": 4, "status": "HIDDEN"},
        }
        html = contact_sheet.render_recount_table(recount)
        self.assertIn("status-offered", html)
        self.assertIn("status-hidden", html)
        self.assertIn("Авангард", html)
        self.assertNotIn("style=", html)


class CardRendering(unittest.TestCase):
    def test_kept_card_without_thumb_shows_missing_placeholder(self):
        entry = {"id": 1, "title": "T", "artist": "A", "year": "1900", "categories": ["era:19c"]}
        html = contact_sheet.render_kept_card(entry, data_uri=None)
        self.assertIn("thumb-missing", html)
        self.assertIn("нет превью", html)
        self.assertNotIn("style=", html)

    def test_kept_card_with_thumb_embeds_data_uri(self):
        entry = {"id": 1, "title": "T", "artist": "A", "year": "1900", "categories": []}
        html = contact_sheet.render_kept_card(entry, data_uri="data:image/jpeg;base64,AAAA")
        self.assertIn("data:image/jpeg;base64,AAAA", html)
        self.assertNotIn("thumb-missing", html)

    def test_dropped_card_shows_reason(self):
        d = {"id": 2, "title": "T2", "reason": "non-painting:manifesto"}
        html = contact_sheet.render_dropped_card(d, data_uri=None)
        self.assertIn("non-painting:manifesto", html)
        self.assertIn("chip-reason", html)


class ThumbDataUri(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def test_missing_file_returns_none(self):
        self.assertIsNone(contact_sheet.make_thumb_data_uri(
            os.path.join(self.tmp, "does-not-exist.jpg")))

    def test_real_image_downsizes_and_encodes(self):
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("PIL not installed")
        path = os.path.join(self.tmp, "big.jpg")
        Image.new("RGB", (1000, 600), color=(10, 20, 30)).save(path, format="JPEG")
        uri = contact_sheet.make_thumb_data_uri(path, max_edge=200)
        self.assertTrue(uri.startswith("data:image/jpeg;base64,"))
        # round-trip: decoded thumb must respect the max_edge cap
        import base64, io
        raw = base64.b64decode(uri.split(",", 1)[1])
        with Image.open(io.BytesIO(raw)) as im:
            self.assertLessEqual(max(im.size), 200)


class RenderFailsClosed(unittest.TestCase):
    """COMMIT_REVIEW finding 3: generation must FAIL (raise ContactSheetError) rather
    than silently emit text-only cards for kept paintings while the page advertises the
    stored approval hash."""
    def setUp(self):
        self.tmp = tempfile.mkdtemp()

    def _vetted(self, kept_id=1):
        kept = [{"id": kept_id, "title": "T", "artist": "A", "year": "1900",
                 "categories": ["era:19c"]}]
        # a stored hash that the (empty/garbage) sources cannot reproduce
        return {
            "kept": kept,
            "dropped": [],
            "kept_count": 1,
            "dropped_count": 0,
            "preset_recount": {
                "ALL": {"label": "Всё", "count": 1, "min": 1, "status": "OFFERED"},
                "AVANTGARDE": {"label": "Авангард", "count": 0, "min": 4, "status": "HIDDEN"},
            },
            "approved_snapshot_sha256": "0" * 64,
        }

    def test_missing_source_fails(self):
        # no source file on disk for the kept id -> no preview -> fatal
        with self.assertRaises(contact_sheet.ContactSheetError):
            contact_sheet.render_html(self._vetted(kept_id=1), self.tmp)

    def test_undecodable_source_fails(self):
        # a present-but-garbage source cannot be decoded into a preview -> fatal
        with open(os.path.join(self.tmp, "1.jpg"), "wb") as f:
            f.write(b"this is not a valid image")
        with self.assertRaises(contact_sheet.ContactSheetError):
            contact_sheet.render_html(self._vetted(kept_id=1), self.tmp)

    def test_snapshot_mismatch_fails_when_previews_ok(self):
        # real decodable image present, but the stored hash does not match the live
        # bytes -> integrity failure (skipped if Pillow is unavailable).
        try:
            from PIL import Image
        except ImportError:
            self.skipTest("PIL not installed")
        Image.new("RGB", (64, 48), color=(1, 2, 3)).save(
            os.path.join(self.tmp, "1.jpg"), format="JPEG")
        with self.assertRaises(contact_sheet.ContactSheetError):
            contact_sheet.render_html(self._vetted(kept_id=1), self.tmp)


if __name__ == "__main__":
    unittest.main(verbosity=2)
