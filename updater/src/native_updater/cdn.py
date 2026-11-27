"""Builds of the native client from the Jagex CDN, the same place the Jagex Launcher installs them from.

  alias.json                         {"osrs-win.<environment>": <catalog id>}: production (what the launcher
                                     installs), production-last (the build before it), staging and previews
  catalog/<id>/catalog.json          where the build's metafile and pieces are, and the piece format
  metafile/<id>/metafile.json        {"version": "243.1", "scanTime": <unix time>, "files": [{name, size}],
                                     "pieces": {"algorithm": "sha256", "digests": [base64, ...]}, "pad": [...]}
  pieces/<hh>/<hex digest>.solidpiece  one piece: a 6-byte header, then gzip

All three documents are JWTs; only their payloads are read. The files lie back to back across the pieces in the
metafile's order, and each digest is the SHA-256 of its piece's decompressed bytes. The metafile's `pad` entries
describe zero padding up to each piece boundary that is never stored, so the decompressed pieces join into the files
as they are. The JWT signatures are not checked: a downloaded build is only read, never run, and every piece is
checked against the metafile's digest.
"""
import base64
import gzip
import hashlib
import http.client
import json
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

BASE = "https://jagex.akamaized.net/direct6/osrs-win"
PIECE_FORMAT = "pieces/{SubString:0,2,{TargetDigest}}/{TargetDigest}.solidpiece"
PIECE_HEADER = 6
GZIP_MAGIC = b"\x1f\x8b"
USER_AGENT = "native-updater"
ATTEMPTS = 3


@dataclass
class Release:
    """One build in the CDN: its catalog and the metafile that lists its files and pieces."""
    catalog: str
    version: str                                   # the metafile's version, e.g. "243.1"
    scan_time: int                                 # when Jagex packaged the build (unix seconds)
    files: list = field(default_factory=list)      # [{"name": ..., "size": ...}] in stream order
    digests: list = field(default_factory=list)    # hex SHA-256 of each piece, in stream order
    environment: str = None                        # the alias it was resolved from, if any

    def file(self, name):
        """(offset in the joined pieces, size) of a file, or a LookupError naming the files there are."""
        offset = 0
        for f in self.files:
            if f["name"] == name:
                return offset, f["size"]
            offset += f["size"]
        raise LookupError(f"build {self.version} has no {name}; it has {', '.join(f['name'] for f in self.files)}")


def http_get(url):
    """The body of a GET, retried a few times on network errors and cut-off responses."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except (OSError, http.client.HTTPException):
            if attempt == ATTEMPTS:
                raise
            time.sleep(2 * attempt)


def jwt_payload(token):
    """The claims of a JWT, without checking its signature."""
    text = token.decode("ascii") if isinstance(token, bytes) else token
    parts = text.strip().split(".")
    if len(parts) != 3:
        raise ValueError(f"expected a JWT, got {text[:40]!r}")
    body = parts[1] + "=" * (-len(parts[1]) % 4)
    return json.loads(base64.urlsafe_b64decode(body))


def resolve(environment="production", catalog=None, fetch=http_get):
    """The build an environment alias points at, or the build with a known catalog id."""
    if catalog is None:
        aliases = jwt_payload(fetch(f"{BASE}/alias.json"))
        alias = f"osrs-win.{environment}"
        catalog = aliases.get(alias)
        if not catalog:
            known = sorted(k.split(".", 1)[1] for k in aliases if k.startswith("osrs-win."))
            raise LookupError(f"alias.json has no {alias}; it has {', '.join(known)}")
    else:
        environment = None
    cat = jwt_payload(fetch(f"{BASE}/catalog/{catalog}/catalog.json"))
    remote = cat.get("config", {}).get("remote", {})
    if remote.get("pieceFormat", PIECE_FORMAT) != PIECE_FORMAT or "gzip" not in remote.get("flags", "gzip"):
        raise ValueError(f"catalog {catalog} stores its pieces as {remote.get('pieceFormat')!r} "
                         f"(flags {remote.get('flags')!r}), which this updater can't read")
    meta = jwt_payload(fetch(_on_base(cat.get("metafile") or f"{BASE}/metafile/{catalog}/metafile.json")))
    pieces = meta.get("pieces", {})
    if pieces.get("algorithm", "sha256") != "sha256" or pieces.get("hashPadding"):
        raise ValueError(f"build {meta.get('version')} hashes its pieces as {pieces.get('algorithm')!r} "
                         f"(hashPadding {pieces.get('hashPadding')!r}), which this updater can't check")
    return Release(catalog=catalog, version=str(meta.get("version", "")), scan_time=int(meta.get("scanTime", 0)),
                   files=[{"name": f["name"], "size": int(f["size"])} for f in meta.get("files", [])],
                   digests=[base64.b64decode(d, validate=True).hex() for d in pieces.get("digests", [])],
                   environment=environment)


def download(release, dest_dir, name="osclient.exe", fetch=http_get):
    """Download one file of a build into dest_dir and return its path. Every piece is checked against its digest."""
    offset, size = release.file(name)
    joined = bytearray()
    for digest in release.digests:
        if len(joined) >= offset + size:
            break
        joined += piece(fetch(f"{BASE}/pieces/{digest[:2]}/{digest}.solidpiece"), digest)
    data = bytes(joined[offset:offset + size])
    if len(data) != size:
        raise ValueError(f"the pieces of build {release.version} hold {len(joined)} bytes, too few for {name} "
                         f"({size} bytes at {offset})")
    path = Path(dest_dir) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def piece(raw, digest):
    """The decompressed bytes of one stored piece, checked against its hex digest."""
    if raw[PIECE_HEADER:PIECE_HEADER + 2] != GZIP_MAGIC:
        raise ValueError(f"piece {digest[:16]} does not hold gzip data after its {PIECE_HEADER}-byte header")
    data = gzip.decompress(raw[PIECE_HEADER:])
    actual = hashlib.sha256(data).hexdigest()
    if actual != digest:
        raise ValueError(f"piece {digest[:16]} decompresses to bytes with SHA-256 {actual[:16]}")
    return data


def _on_base(url):
    """A CDN URL moved onto BASE (the catalogs name a plain-HTTP host); other URLs are returned unchanged."""
    marker = "/direct6/osrs-win/"
    return f"{BASE}/{url.split(marker, 1)[1]}" if marker in url else url
