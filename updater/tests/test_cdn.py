"""The Jagex CDN client (cdn.py) over a fake CDN: the aliases, a catalog, its metafile and gzip pieces behind their
6-byte header, served from a dict instead of the network."""
import base64
import gzip
import hashlib
import http.client
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from native_updater import cdn

CATALOG = "ab" * 32
HEADER = bytes.fromhex("6e2b3b610002")


def jwt(claims):
    def part(obj):
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()
    return f"{part({'alg': 'RS256', 'typ': 'JWT'})}.{part(claims)}.c2lnbmF0dXJl".encode()


class FakeCdn:
    """A build whose files lie back to back across pieces of piece_size bytes, as the metafile describes them."""

    def __init__(self, files, piece_size, piece_format=cdn.PIECE_FORMAT, hash_padding=False):
        stream = b"".join(data for _, data in files)
        pieces = [stream[i:i + piece_size] for i in range(0, len(stream), piece_size)]
        self.digests = [hashlib.sha256(p).hexdigest() for p in pieces]
        self.routes = {
            f"{cdn.BASE}/alias.json": jwt({"osrs-win.production": CATALOG, "osrs-win.staging": "cd" * 32}),
            f"{cdn.BASE}/catalog/{CATALOG}/catalog.json": jwt({
                "id": CATALOG, "config": {"remote": {"pieceFormat": piece_format, "flags": "gzip"}},
                "metafile": f"http://jagex-akamai.aws.snxd.com/direct6/osrs-win/metafile/{CATALOG}/metafile.json"}),
            f"{cdn.BASE}/metafile/{CATALOG}/metafile.json": jwt({
                "version": "243.2", "scanTime": 1795168076, "pad": [{"offset": 7, "size": 9}],
                "files": [{"attr": 5, "name": name, "size": len(data)} for name, data in files],
                "pieces": {"algorithm": "sha256", "hashPadding": hash_padding,
                           "digests": [base64.b64encode(bytes.fromhex(d)).decode() for d in self.digests]}}),
        }
        for digest, data in zip(self.digests, pieces):
            self.routes[f"{cdn.BASE}/pieces/{digest[:2]}/{digest}.solidpiece"] = HEADER + gzip.compress(data)
        self.requests = []

    def __call__(self, url):
        self.requests.append(url)
        if url not in self.routes:
            raise OSError(f"404 {url}")
        return self.routes[url]


DLL = bytes(range(256)) * 3
EXE = b"MZ" + bytes(range(255, 0, -1)) * 5


class Resolve(unittest.TestCase):
    def test_follows_the_production_alias_to_the_metafile(self):
        fake = FakeCdn([("discord_game_sdk.dll", DLL), ("osclient.exe", EXE)], 500)
        release = cdn.resolve(fetch=fake)
        self.assertEqual((release.catalog, release.version, release.scan_time, release.environment),
                         (CATALOG, "243.2", 1795168076, "production"))
        self.assertEqual([f["name"] for f in release.files], ["discord_game_sdk.dll", "osclient.exe"])
        self.assertEqual(release.digests, fake.digests)
        self.assertTrue(all(u.startswith(cdn.BASE) for u in fake.requests), "the catalog's plain-HTTP host is not used")

    def test_a_catalog_id_skips_the_aliases(self):
        fake = FakeCdn([("osclient.exe", EXE)], 500)
        release = cdn.resolve(catalog=CATALOG, fetch=fake)
        self.assertIsNone(release.environment)
        self.assertNotIn(f"{cdn.BASE}/alias.json", fake.requests)

    def test_an_unknown_alias_names_the_ones_there_are(self):
        fake = FakeCdn([("osclient.exe", EXE)], 500)
        with self.assertRaisesRegex(LookupError, "production, staging"):
            cdn.resolve("beta", fetch=fake)

    def test_refuses_a_piece_format_it_cannot_read(self):
        fake = FakeCdn([("osclient.exe", EXE)], 500, piece_format="chunks/{TargetDigest}.bin")
        with self.assertRaisesRegex(ValueError, "can't read"):
            cdn.resolve(fetch=fake)

    def test_refuses_digests_that_cover_the_padding(self):
        fake = FakeCdn([("osclient.exe", EXE)], 500, hash_padding=True)
        with self.assertRaisesRegex(ValueError, "can't check"):
            cdn.resolve(fetch=fake)

    def test_a_jwt_has_three_parts(self):
        with self.assertRaises(ValueError):
            cdn.jwt_payload(b"{\"osrs-win.production\": \"ab\"}")


class HttpGet(unittest.TestCase):
    def test_a_cut_off_response_is_retried(self):
        class Response:
            def __init__(self, body):
                self.body = body

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def read(self):
                if self.body is None:
                    raise http.client.IncompleteRead(b"part")
                return self.body

        replies = [Response(None), Response(b"whole")]
        with mock.patch("urllib.request.urlopen", side_effect=lambda *a, **k: replies.pop(0)), \
                mock.patch("time.sleep"):
            self.assertEqual(cdn.http_get(f"{cdn.BASE}/alias.json"), b"whole")

    def test_gives_up_after_the_last_attempt(self):
        with mock.patch("urllib.request.urlopen", side_effect=OSError("down")) as urlopen, mock.patch("time.sleep"):
            with self.assertRaises(OSError):
                cdn.http_get(f"{cdn.BASE}/alias.json")
        self.assertEqual(urlopen.call_count, cdn.ATTEMPTS)


class Download(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.dest = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def test_joins_the_pieces_and_cuts_out_the_file(self):
        fake = FakeCdn([("discord_game_sdk.dll", DLL), ("osclient.exe", EXE)], 500)
        path = cdn.download(cdn.resolve(fetch=fake), self.dest, fetch=fake)
        self.assertEqual(path, self.dest / "osclient.exe")
        self.assertEqual(path.read_bytes(), EXE)

    def test_stops_once_the_file_is_covered(self):
        fake = FakeCdn([("discord_game_sdk.dll", DLL), ("osclient.exe", EXE)], 500)
        release = cdn.resolve(fetch=fake)
        fake.requests.clear()
        path = cdn.download(release, self.dest, "discord_game_sdk.dll", fetch=fake)
        self.assertEqual(path.read_bytes(), DLL)
        self.assertEqual(len(fake.requests), 2, "the dll ends in the second of five pieces")

    def test_a_piece_that_does_not_match_its_digest_fails(self):
        fake = FakeCdn([("osclient.exe", EXE)], 500)
        release = cdn.resolve(fetch=fake)
        d = fake.digests[1]
        fake.routes[f"{cdn.BASE}/pieces/{d[:2]}/{d}.solidpiece"] = HEADER + gzip.compress(b"something else")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            cdn.download(release, self.dest, fetch=fake)
        self.assertFalse((self.dest / "osclient.exe").exists())

    def test_a_piece_without_gzip_after_the_header_fails(self):
        fake = FakeCdn([("osclient.exe", EXE)], 500)
        release = cdn.resolve(fetch=fake)
        d = fake.digests[0]
        fake.routes[f"{cdn.BASE}/pieces/{d[:2]}/{d}.solidpiece"] = gzip.compress(EXE[:500])
        with self.assertRaisesRegex(ValueError, "gzip"):
            cdn.download(release, self.dest, fetch=fake)

    def test_an_unknown_file_names_the_ones_there_are(self):
        fake = FakeCdn([("discord_game_sdk.dll", DLL), ("osclient.exe", EXE)], 500)
        with self.assertRaisesRegex(LookupError, "discord_game_sdk.dll, osclient.exe"):
            cdn.download(cdn.resolve(fetch=fake), self.dest, "osclient.dll", fetch=fake)


if __name__ == "__main__":
    unittest.main()
