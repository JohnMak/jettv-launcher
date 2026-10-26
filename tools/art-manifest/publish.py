#!/usr/bin/env python3
"""Step 3: immutable publish of the owner-approved vetted art set to art.jmwork.ru.

BUILD-TIME ONLY. Downscales the ALREADY-REVIEWED source snapshots in sources/ (the exact
bytes the owner signed off in Step 2 — NO re-download), content-addresses each derivative,
uploads under an immutable name, stages a slim manifest, validates the staged set over
HTTPS, and only then atomically promotes the manifest pointer on the Caddy VPS.

Design (plan Rev5, decisions D3/D4/D8):
  * Downscale each reviewed source to <=1920px long edge, JPEG q85.
  * Content-address the DERIVATIVE: file = "<id>-<sha1(derivative)[0:12]>.jpg". Same bytes
    -> same name; re-encoded bytes -> new name. A published image is NEVER overwritten.
  * Slim manifest: {base_url, generated_at} + entries {id,title,year,categories,file,sha1}.
    Caption is title . year (D4 — artist omitted from the published manifest).
  * Retention WITHOUT pruning (Rev5): publish is append-only. We only ADD image files and
    swap the single mutable pointer (manifest.json). We NEVER delete server content.
  * Staged promote: upload manifest.json.staged, validate the staged set (validate_published),
    then atomic `mv staged -> live` on the server. A failed validation leaves live untouched.

Approval binding (fail-closed, STOP+escalate on mismatch): publish refuses to run unless
vetted.json is approved, its stored approved_snapshot_sha256 starts with 700a085c, the hash
recomputes from the committed per-id digests, AND the live source bytes reproduce it. Any
drift between the reviewed set and what we are about to publish aborts the publish.

Terminal fetch rule (plan/brain): publish downscales CACHED originals only. If any approved
source is missing locally it raises SourceFetchRequired -> the caller STOPS and escalates
(costed fetch-vs-bundle decision). publish.py performs NO network fetch of sources.

Usage:
    python3 publish.py --target ssh                # publish to art.jmwork.ru (default ssh)
    python3 publish.py --target local --root DIR   # publish into a local webroot (tests/dry)
    python3 publish.py --no-promote                # upload + validate staged, do NOT promote
"""
import argparse, hashlib, io, json, os, subprocess, sys, time
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import curate  # noqa: E402
# validate_published OWNS the published-set contract constants (no cycle: it never imports
# publish). Importing them here keeps producer and validator from ever drifting.
import validate_published as vp  # noqa: E402

# ---- published-set contract (single source of truth = validate_published) ----------------
BASE_URL = vp.BASE_URL
IMAGE_MAX_EDGE = vp.IMAGE_MAX_EDGE
IMAGE_MAX_BYTES = vp.IMAGE_MAX_BYTES          # per-image byte cap
MANIFEST_MAX_BYTES = vp.MANIFEST_MAX_BYTES    # manifest byte cap
MAX_MANIFEST_ENTRIES = vp.MAX_MANIFEST_ENTRIES  # entry-count cap
IMAGES_DIR = vp.IMAGES_PREFIX.rstrip("/")
MANIFEST_NAME = vp.MANIFEST_NAME
# ---- publish-only knobs ------------------------------------------------------------------
IMAGE_QUALITY = 85                            # JPEG re-encode quality (D8: q85)
APPROVED_HASH_PREFIX = "700a085c"             # owner-approved 19-painting set (Step 2)
# Staged manifests use a per-CONTENT unique name (sha1 of the manifest bytes). Two effects
# (COMMIT_REVIEW P1): (a) a concurrent publish of DIFFERENT content can never replace the
# bytes we validated under the SAME staged name; (b) promote re-binds to the validated bytes
# before the atomic rename. A no-op retry of identical content reuses the same name (same
# bytes) — safe.
STAGED_PREFIX = "manifest.json.staged."
TEMP_SUFFIX = ".part"                         # interrupted-upload temp marker (never retained)


def staged_name_for(manifest_bytes_blob):
    """Per-content unique staged name (P1): the sha1 of the exact bytes we will validate."""
    return STAGED_PREFIX + sha1_hex(manifest_bytes_blob)[:16]


def unique_temp(path):
    """Per-UPLOAD unique temp path (COMMIT_REVIEW P2): `<path>.<pid>.<rand>.part`. Concurrent or
    retried uploads never share a temp path, so one writer can never truncate/overwrite another's
    partial data. Ends with TEMP_SUFFIX so it is excluded from the retention inventory and is
    never mistaken for published content."""
    return "%s.%d.%s%s" % (path, os.getpid(), os.urandom(8).hex(), TEMP_SUFFIX)


class PublishError(RuntimeError):
    """Fatal publish error; the live manifest is never touched on any failure."""


class ApprovalMismatch(PublishError):
    """vetted.json is not the owner-approved 700a085c set (approved flag / hash drift).
    STOP + escalate — never publish an unapproved or mutated set."""


class SourceFetchRequired(PublishError):
    """A reviewed source snapshot is missing locally. Terminal fetch rule: publish must
    NOT fetch new sources; STOP + escalate the costed fetch-vs-bundle decision."""


# ====================== pure helpers (offline-unit-testable) ==============================

def sha1_hex(data):
    return hashlib.sha1(data).hexdigest()


def downscale_jpeg(data, max_edge=IMAGE_MAX_EDGE, quality=IMAGE_QUALITY):
    """Pure (bytes -> bytes): decode `data`, flatten to RGB, LANCZOS-downscale so the long
    edge is <= max_edge (never upscales), re-encode baseline JPEG at `quality` (optimized).
    Raises PublishError if the bytes do not decode as an image."""
    try:
        from PIL import Image
    except ImportError as ex:  # pragma: no cover - environment guard
        raise PublishError("Pillow (PIL) is required to downscale images: %s" % ex)
    try:
        with Image.open(io.BytesIO(data)) as im:
            im = im.convert("RGB")
            w, h = im.size
            scale = min(1.0, max_edge / float(max(w, h)))
            if scale < 1.0:
                im = im.resize((max(1, int(w * scale)), max(1, int(h * scale))),
                               Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, format="JPEG", quality=quality, optimize=True)
            return buf.getvalue()
    except PublishError:
        raise
    except Exception as ex:
        raise PublishError("cannot decode/downscale image: %s" % ex)


def derivative_filename(entry_id, derivative_sha1):
    """Immutable content-addressed name (D8): <id>-<sha1[0:12]>.jpg. Deterministic in the
    id and the derivative bytes; two different byte streams can never collide on a name
    unless both their ids and 48-bit sha1 prefixes match."""
    return "%s-%s.jpg" % (entry_id, derivative_sha1[:12])


def build_entry(kept_entry, file_name, derivative_sha1):
    """Slim published entry (D3/D4): id,title,year,categories,file,sha1 — artist omitted.
    Order fixed for a stable manifest byte image."""
    return OrderedDict([
        ("id", kept_entry["id"]),
        ("title", kept_entry["title"]),
        ("year", kept_entry["year"]),
        ("categories", list(kept_entry["categories"])),
        ("file", file_name),
        ("sha1", derivative_sha1),
    ])


def build_manifest(entries, generated_at, base_url=BASE_URL):
    return OrderedDict([
        ("base_url", base_url),
        ("generated_at", generated_at),
        ("entries", list(entries)),
    ])


def manifest_bytes(manifest):
    """Canonical UTF-8 manifest bytes (what gets uploaded AND size-checked)."""
    return json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def verify_approval(vetted, sources_dir, expected_prefix=APPROVED_HASH_PREFIX):
    """Fail-closed approval gate. Returns the stored approved hash on success; raises
    ApprovalMismatch otherwise. Binds four facts together:
      1. approved flag is true,
      2. stored approved_snapshot_sha256 starts with the owner-approved prefix,
      3. the hash recomputes from the COMMITTED per-id source digests (checkout integrity),
      4. the LIVE source bytes reproduce the same hash (what we publish == what was reviewed).
    """
    # Strict boolean (COMMIT_REVIEW P2): truthiness lets "false"/1/"yes" through the gate.
    # The approved flag must be the JSON boolean `true` and nothing else.
    if vetted.get("approved") is not True:
        raise ApprovalMismatch(
            "vetted.json approved flag is %r, not boolean true" % (vetted.get("approved"),))
    stored = vetted.get("approved_snapshot_sha256") or ""
    if not stored.startswith(expected_prefix):
        raise ApprovalMismatch(
            "approved_snapshot_sha256 %r does not start with %r" % (stored, expected_prefix))
    kept = vetted["kept"]
    from_digests = curate.snapshot_hash_from_digests(kept, vetted["source_sha1"])
    if from_digests != stored:
        raise ApprovalMismatch(
            "hash from committed digests %s != stored %s" % (from_digests, stored))
    live = curate.compute_snapshot_hash(kept, sources_dir)
    if live != stored:
        raise ApprovalMismatch(
            "live source bytes hash %s != stored %s (sources/ out of sync with vetted.json)"
            % (live, stored))
    return stored


def verify_source_bytes(raw, entry_id, source_sha1):
    """Exact-buffer approval binding (COMMIT_REVIEW P2 — source-approval race).

    The ONLY bytes that may be downscaled/published are the reviewed source bytes the owner
    approved. verify_approval checks the on-disk set once; a replacement between that read and
    the encoder's read would publish unapproved bytes whose derivative still validates. So we
    verify the EXACT buffer handed to the encoder: sha1(raw) MUST equal the committed per-id
    digest (which verify_approval already bound to the approved 700a085c snapshot). Raises
    ApprovalMismatch on any drift or a missing committed digest."""
    expected = source_sha1.get(str(entry_id))
    actual = sha1_hex(raw)
    if expected is None or actual != expected:
        raise ApprovalMismatch(
            "source id=%s buffer sha1 %s != approved digest %r — the bytes handed to the "
            "encoder are not the reviewed/approved source" % (entry_id, actual, expected))


def _find_source(sources_dir, entry_id):
    for ext in ("jpg", "jpeg", "png"):
        p = os.path.join(sources_dir, "%s.%s" % (entry_id, ext))
        if os.path.exists(p):
            return p
    return None


def resolve_sources(kept, sources_dir):
    """Map every kept entry to its cached source path. Raises SourceFetchRequired (terminal
    fetch rule) if ANY is absent — we never fetch new sources at publish time."""
    missing, resolved = [], []
    for e in kept:
        p = _find_source(sources_dir, e["id"])
        if p is None:
            missing.append(e["id"])
        else:
            resolved.append((e, p))
    if missing:
        raise SourceFetchRequired(
            "%d approved source(s) not cached locally: %s — publish must NOT fetch; "
            "escalate the costed fetch-vs-bundle decision" % (len(missing), missing))
    return resolved


def build_payload(vetted, sources_dir, generated_at, approved_prefix=APPROVED_HASH_PREFIX):
    """Pure-ish (reads source bytes): returns (manifest_dict, {file_name: derivative_bytes}).
    Verifies approval first, then downscales each reviewed source, content-addresses it, and
    assembles the slim manifest. Enforces the manifest entry-count and byte caps up front so
    an over-cap set never reaches the server."""
    # P3 (safety-critical ordering): check source PRESENCE first so a genuinely missing
    # approved source raises the terminal SourceFetchRequired (STOP + escalate the costed
    # fetch-vs-bundle decision) instead of being masked by verify_approval's ApprovalMismatch
    # (a missing source yields a None digest -> hash drift -> ApprovalMismatch). The terminal
    # fetch rule must fire on a truly absent source, not get swallowed by the approval gate.
    resolved = resolve_sources(vetted["kept"], sources_dir)
    verify_approval(vetted, sources_dir, approved_prefix)
    entries, images = [], OrderedDict()
    source_sha1 = vetted["source_sha1"]
    for e, path in resolved:
        with open(path, "rb") as f:
            raw = f.read()
        # P2: bind the EXACT buffer we encode to the approved per-id digest (race close).
        verify_source_bytes(raw, e["id"], source_sha1)
        derivative = downscale_jpeg(raw)
        if len(derivative) > IMAGE_MAX_BYTES:
            raise PublishError(
                "derivative for id=%s is %d bytes > IMAGE_MAX_BYTES %d"
                % (e["id"], len(derivative), IMAGE_MAX_BYTES))
        dsha1 = sha1_hex(derivative)
        fname = derivative_filename(e["id"], dsha1)
        images[fname] = derivative
        entries.append(build_entry(e, fname, dsha1))
    if len(entries) > MAX_MANIFEST_ENTRIES:
        raise PublishError("entry count %d > MAX_MANIFEST_ENTRIES %d"
                           % (len(entries), MAX_MANIFEST_ENTRIES))
    manifest = build_manifest(entries, generated_at)
    mbytes = manifest_bytes(manifest)
    if len(mbytes) > MANIFEST_MAX_BYTES:
        raise PublishError("manifest is %d bytes > MANIFEST_MAX_BYTES %d"
                           % (len(mbytes), MANIFEST_MAX_BYTES))
    return manifest, images


# ============================== transports (upload only) ==================================

class Transport(object):
    """Upload surface. Implementations MUST be append-only: add image files and swap the
    single manifest pointer; NEVER delete existing server content."""

    def existing_images(self):
        raise NotImplementedError

    def put_image(self, file_name, data):
        """Upload an image under its immutable name. If a file of that name already exists
        it is left untouched (content-addressed => identical bytes); never overwritten."""
        raise NotImplementedError

    def put_staged_manifest(self, staged_name, data):
        """Upload the staged manifest under a per-content unique name (P1)."""
        raise NotImplementedError

    def promote(self, staged_name, expected_bytes):
        """Atomic same-filesystem rename staged_name -> live (the only mutable pointer).
        MUST re-bind to `expected_bytes` (the exact bytes that passed staged validation)
        immediately before the rename and refuse to promote anything else (P1)."""
        raise NotImplementedError


class LocalTransport(Transport):
    """Publish into a local directory tree (dry-run / offline tests). Mirrors the SSH
    transport's append-only semantics exactly."""

    def __init__(self, root):
        self.root = root
        self.images_dir = os.path.join(root, IMAGES_DIR)
        os.makedirs(self.images_dir, exist_ok=True)

    def existing_images(self):
        if not os.path.isdir(self.images_dir):
            return set()
        # P2 (interrupted-upload retry): never count `.part` temp files in the retention
        # inventory — a leftover partial is not published content, and letting it in makes
        # a later `mv part -> final` look like an append-only violation AFTER promotion.
        return set(x for x in os.listdir(self.images_dir)
                   if not x.endswith(TEMP_SUFFIX))

    def put_image(self, file_name, data):
        dest = os.path.join(self.images_dir, file_name)
        if os.path.exists(dest):
            return False  # append-only: never overwrite an existing immutable name
        return self._atomic_install(dest, data)

    @staticmethod
    def _atomic_install(dest, data):
        """Write to a per-UPLOAD unique temp, then atomically hard-link it into place (P2).

        os.link is atomic and REFUSES to overwrite an existing destination (FileExistsError), so
        two concurrent/retried uploads never clobber each other and a destination that appears
        after put_image's existence check is left intact (content-addressed => identical bytes).
        The unique temp means concurrent writers never share a temp path and cannot truncate each
        other's partial data. Returns True iff THIS call created the file."""
        tmp = unique_temp(dest)
        with open(tmp, "wb") as f:
            f.write(data)
        try:
            os.link(tmp, dest)   # atomic, no-clobber land (replaces the old mv -f overwrite)
            return True
        except FileExistsError:
            return False         # a concurrent writer landed identical content first — leave it
        finally:
            try:
                os.unlink(tmp)
            except OSError:
                pass

    def put_staged_manifest(self, staged_name, data):
        dest = os.path.join(self.root, staged_name)
        tmp = unique_temp(dest)  # unique temp: concurrent stages never share/truncate a temp path
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, dest)  # per-content name => an atomic refresh with identical bytes

    def promote(self, staged_name, expected_bytes):
        staged = os.path.join(self.root, staged_name)
        with open(staged, "rb") as f:
            actual = f.read()
        if actual != expected_bytes:  # P1: refuse to promote anything but the validated bytes
            raise PublishError(
                "PROMOTE ABORTED: staged %s bytes changed since validation "
                "(%d bytes, sha1 %s != validated sha1 %s); live manifest untouched"
                % (staged_name, len(actual), sha1_hex(actual), sha1_hex(expected_bytes)))
        os.replace(staged, os.path.join(self.root, MANIFEST_NAME))


class SshTransport(Transport):
    """Publish to the Caddy VPS over SSH. Append-only: writes images to a per-UPLOAD unique
    temp then atomically hard-links them into place with `ln` (atomic, refuses to overwrite an
    existing destination — COMMIT_REVIEW P2), never deletes; promote is a same-filesystem `mv`.
    Files are made world-readable (Caddy runs as a different user)."""

    def __init__(self, host, user, key, webroot, port=22):
        self.host = host
        self.user = user
        self.key = key
        self.webroot = webroot.rstrip("/")
        self.port = port
        self._ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
                     "-o", "StrictHostKeyChecking=accept-new",
                     "-i", key, "-p", str(port), "%s@%s" % (user, host)]
        # ensure the images dir exists and is traversable by Caddy
        self._run(["mkdir", "-p", self._q(self.webroot + "/" + IMAGES_DIR), "&&",
                   "chmod", "755", self._q(self.webroot), self._q(self.webroot + "/" + IMAGES_DIR)])

    @staticmethod
    def _q(s):
        return "'" + s.replace("'", "'\\''") + "'"

    def _run(self, remote_argv, input_bytes=None):
        cmd = self._ssh + [" ".join(remote_argv)]
        p = subprocess.run(cmd, input=input_bytes,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if p.returncode != 0:
            raise PublishError("ssh failed (%d): %s" % (
                p.returncode, p.stderr.decode("utf-8", "replace").strip()))
        return p.stdout

    def existing_images(self):
        out = self._run(["ls", "-1", self._q(self.webroot + "/" + IMAGES_DIR),
                         "2>/dev/null", "||", "true"])
        # P2: exclude `.part` temp files from the retention inventory (see LocalTransport).
        return set(x for x in out.decode("utf-8", "replace").split("\n")
                   if x and not x.endswith(TEMP_SUFFIX))

    def put_image(self, file_name, data):
        if file_name in self.existing_images():
            return False
        remote = self.webroot + "/" + IMAGES_DIR + "/" + file_name
        part = unique_temp(remote)  # per-upload unique temp: no shared-path truncation (P2)
        # Atomic no-clobber install (P2): `ln` is atomic and FAILS if the destination exists, so
        # a destination that appeared after the inventory check is never overwritten (content-
        # addressed => identical bytes already there). The old `mv -f` clobbered; it is gone. We
        # always `rm` the temp; the final `test -e` makes the command succeed iff the immutable
        # destination exists afterwards (whether THIS call linked it or a concurrent writer did).
        self._run(["cat", ">", self._q(part), "&&",
                   "chmod", "644", self._q(part), "&&",
                   "(", "ln", self._q(part), self._q(remote), "2>/dev/null", "||", "true", ")",
                   "&&", "rm", "-f", self._q(part), "&&", "test", "-e", self._q(remote)],
                  input_bytes=data)
        return True

    def put_staged_manifest(self, staged_name, data):
        remote = self.webroot + "/" + staged_name
        part = unique_temp(remote)  # per-upload unique temp: no shared-path truncation (P2)
        # The staged name is content-addressed, so an atomic `mv -f` here is an intended refresh
        # with identical bytes (never a foreign overwrite); promotion is byte-bound separately.
        self._run(["cat", ">", self._q(part), "&&",
                   "chmod", "644", self._q(part), "&&",
                   "mv", "-f", self._q(part), self._q(remote)], input_bytes=data)

    def promote(self, staged_name, expected_bytes):
        staged = self.webroot + "/" + staged_name
        live = self.webroot + "/" + MANIFEST_NAME
        expected_sha1 = sha1_hex(expected_bytes)
        # P1: verify the staged bytes still equal the validated bytes and atomically rename in
        # ONE remote shell invocation (no promote if the sha1 drifted). Combined with the
        # per-content unique staged name, a concurrent publish can neither overwrite these
        # exact bytes nor make us promote anything else.
        check = ('test "$(sha1sum %s | cut -c1-40)" = %s'
                 % (self._q(staged), self._q(expected_sha1)))
        self._run([check, "&&", "mv", "-f", self._q(staged), self._q(live)])


# ================================== orchestration =========================================

def publish(vetted, sources_dir, transport, generated_at, validate_staged=None,
            promote=True, log=None, approved_prefix=APPROVED_HASH_PREFIX):
    """Full publish pipeline. Returns a result dict. Fail-closed: any error before promote
    leaves the live manifest untouched; promote happens ONLY after staged validation passes.

    validate_staged: optional callable(staged_name) -> (ok: bool, report: str, raw: bytes)
    run against the staged set (served over HTTPS at the per-content unique staged name) BEFORE
    promote. A falsey ok aborts. `raw` is the EXACT manifest bytes the validator fetched and
    validated; publish binds promotion to those bytes (COMMIT_REVIEW P1): if `raw` is not byte-
    identical to the staged manifest we would promote (a redirect or server-side swap that made
    validation pass on a DIFFERENT manifest), publish aborts and the live pointer is untouched.
    One staged artifact, validated and promoted — identical bytes start to finish.
    """
    log = log or (lambda *a: None)
    manifest, images = build_payload(vetted, sources_dir, generated_at, approved_prefix)
    mbytes = manifest_bytes(manifest)
    staged_name = staged_name_for(mbytes)  # P1: per-content unique staging path

    before = transport.existing_images()
    log("inventory before: %d image(s)" % len(before))

    uploaded, skipped = 0, 0
    for fname, data in images.items():
        if transport.put_image(fname, data):
            uploaded += 1
            log("uploaded images/%s (%d bytes)" % (fname, len(data)))
        else:
            skipped += 1
            log("exists images/%s (append-only skip)" % fname)

    transport.put_staged_manifest(staged_name, mbytes)
    log("staged %s (%d bytes, %d entries)"
        % (staged_name, len(mbytes), len(manifest["entries"])))

    if validate_staged is not None:
        ok, report, validated_bytes = validate_staged(staged_name)
        log("staged validation: %s" % ("PASS" if ok else "FAIL"))
        if not ok:
            raise PublishError("staged validation failed; live manifest NOT promoted:\n" + report)
        # P1 ROOT BINDING: the bytes the validator actually FETCHED and validated must be the
        # exact bytes we are about to promote. A redirect or server-side swap that made
        # validation pass on a DIFFERENT manifest (e.g. the old live one) than the staged file
        # yields validated_bytes != mbytes — abort here. Promotion is bound to the validated
        # artifact itself, not merely to a staged name. A non-bytes/None raw fails closed.
        if validated_bytes != mbytes:
            got = (sha1_hex(validated_bytes)
                   if isinstance(validated_bytes, (bytes, bytearray)) else repr(validated_bytes))
            raise PublishError(
                "VALIDATION BINDING FAILED: the validator saw manifest %s but the staged bytes "
                "to promote are sha1 %s (%d bytes) — validation passed on a DIFFERENT artifact "
                "(redirect or server-side swap); live manifest NOT promoted"
                % (got, sha1_hex(mbytes), len(mbytes)))

    if promote:
        # P1: promote re-binds to the EXACT validated bytes before the atomic rename (defense in
        # depth over the fetched-bytes binding above: catches a staged-file change local to the
        # server between validation and the rename).
        transport.promote(staged_name, mbytes)
        log("promoted %s -> %s (atomic, bytes-bound)" % (staged_name, MANIFEST_NAME))

    after = transport.existing_images()
    # append-only invariant: the image set only ever grows
    if not before.issubset(after):
        raise PublishError("APPEND-ONLY VIOLATION: images disappeared: %s"
                           % sorted(before - after))

    return OrderedDict([
        ("entries", len(manifest["entries"])),
        ("images_uploaded", uploaded),
        ("images_skipped", skipped),
        ("inventory_before", len(before)),
        ("inventory_after", len(after)),
        ("promoted", bool(promote)),
        ("manifest_url", BASE_URL + MANIFEST_NAME),
        ("staged_url", BASE_URL + staged_name),
    ])


def _default_generated_at():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--vetted", default=os.path.join(HERE, "vetted.json"))
    ap.add_argument("--sources", default=os.path.join(HERE, "sources"))
    ap.add_argument("--target", choices=("ssh", "local"), default="ssh")
    ap.add_argument("--root", help="local webroot (for --target local)")
    ap.add_argument("--host", default="130.49.140.13")
    ap.add_argument("--user", default="root")
    ap.add_argument("--key", default=os.path.expanduser("~/.ssh/jmwork_ru_ed25519"))
    ap.add_argument("--port", type=int, default=22)
    ap.add_argument("--webroot", default="/var/www/art")
    ap.add_argument("--generated-at", default=None,
                    help="ISO-8601 UTC override (default: now); set for reproducible runs")
    ap.add_argument("--no-promote", dest="promote", action="store_false",
                    help="upload + validate staged but do NOT promote the live manifest")
    ap.add_argument("--no-validate", dest="validate", action="store_false",
                    help="skip the staged HTTPS validation (NOT recommended)")
    args = ap.parse_args(argv)

    with open(args.vetted) as f:
        vetted = json.load(f)

    if args.target == "local":
        if not args.root:
            sys.stderr.write("FATAL: --target local requires --root DIR\n")
            return 2
        transport = LocalTransport(args.root)
    else:
        transport = SshTransport(args.host, args.user, args.key, args.webroot, args.port)

    generated_at = args.generated_at or _default_generated_at()

    validate_staged = None
    if args.validate and args.target == "ssh":
        def validate_staged(staged_name):  # noqa: E306
            return vp.validate_url(BASE_URL + staged_name)

    def log(msg):
        sys.stderr.write(msg + "\n")

    try:
        result = publish(vetted, args.sources, transport, generated_at,
                         validate_staged=validate_staged, promote=args.promote, log=log)
    except ApprovalMismatch as ex:
        sys.stderr.write("ESCALATE (approval mismatch): %s\n" % ex)
        return 3
    except SourceFetchRequired as ex:
        sys.stderr.write("ESCALATE (source fetch required): %s\n" % ex)
        return 4
    except PublishError as ex:
        sys.stderr.write("FATAL: %s\n" % ex)
        return 1

    sys.stderr.write("PUBLISH OK: %s\n" % json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
