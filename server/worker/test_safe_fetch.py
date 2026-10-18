# python3 -m unittest discover -s server/worker   (stdlib only; DNS is mocked, no network)
import asyncio
import socket
import unittest

import safe_fetch
from safe_fetch import UnsafeURL, validate_url


def resolver_for(mapping):
    def fake(host, port, type=0, **_):
        if host not in mapping:
            raise socket.gaierror("unknown host")
        return [(socket.AF_INET6 if ":" in ip else socket.AF_INET, type, 6, "", (ip, port)) for ip in mapping[host]]
    return fake


HOSTS = {
    "localhost-alias.test": ["127.0.0.1"],
    "internal.test": ["10.1.2.3"],
    "mixed.test": ["93.184.216.34", "192.168.0.5"],
    "example.com": ["93.184.216.34"],
    "v6.example.com": ["2606:2800:220:1:248:1893:25c8:1946"],
    "rebind.test": ["169.254.169.254"],
    "cgnat.test": ["100.64.0.1"],
    "mapped.test": ["::ffff:10.0.0.1"],
}
RESOLVE = resolver_for(HOSTS)


class ValidateUrlTests(unittest.TestCase):
    def rejected(self, url):
        with self.assertRaises(UnsafeURL, msg=url):
            validate_url(url, RESOLVE)

    def test_private_and_special_literals_are_rejected(self):
        for url in [
            "http://127.0.0.1/", "http://127.1.2.3/", "http://10.0.0.1/", "http://172.16.0.1/",
            "http://172.31.255.255/", "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data/",
            "http://0.0.0.0/", "http://100.64.0.1/", "http://224.0.0.1/", "http://240.0.0.1/",
            "http://[::1]/", "http://[fd00::1]/", "http://[fe80::1]/", "http://[::]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.0.0.1]/", "http://[::ffff:a9fe:a9fe]/",
            "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::]/",
        ]:
            self.rejected(url)

    def test_localhost_names_are_rejected(self):
        for url in ["http://localhost/", "http://LOCALHOST:80/", "http://foo.localhost/", "http://localhost./"]:
            self.rejected(url)

    def test_obfuscated_ipv4_forms_are_rejected(self):
        for url in ["http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://127.1/", "http://017700000001/"]:
            self.rejected(url)

    def test_hostnames_resolving_to_private_space_are_rejected(self):
        for url in ["http://localhost-alias.test/", "http://internal.test/", "http://rebind.test/",
                    "http://cgnat.test/", "http://mapped.test/"]:
            self.rejected(url)

    def test_any_private_answer_rejects_the_host(self):
        self.rejected("http://mixed.test/")

    def test_unresolvable_host_is_rejected(self):
        self.rejected("http://nonexistent.test/")

    def test_scheme_port_and_credentials(self):
        for url in ["file:///etc/passwd", "ftp://example.com/", "gopher://example.com/", "http://example.com:8080/",
                    "https://example.com:22/", "http://example.com:0/", "http://user:pw@example.com/",
                    "http://@example.com/", "http:///path", "", "not a url"]:
            self.rejected(url)

    def test_public_urls_are_allowed(self):
        self.assertEqual(validate_url("https://example.com/a?b=1", RESOLVE),
                         ("https", "example.com", 443, ["93.184.216.34"]))
        self.assertEqual(validate_url("http://example.com:80/", RESOLVE)[2], 80)
        self.assertEqual(validate_url("https://example.com:443/", RESOLVE)[2], 443)
        self.assertEqual(validate_url("https://v6.example.com/", RESOLVE)[3], ["2606:2800:220:1:248:1893:25c8:1946"])
        self.assertEqual(validate_url("http://93.184.216.34/", RESOLVE)[3], ["93.184.216.34"])
        self.assertEqual(validate_url("http://[2606:4700:4700::1111]/", RESOLVE)[3], ["2606:4700:4700::1111"])

    def test_is_public_ip(self):
        for ip in ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"]:
            self.assertTrue(safe_fetch.is_public_ip(ip), ip)
        for ip in ["127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "0.0.0.0",
                   "100.64.0.1", "::1", "fd00::1", "::ffff:127.0.0.1", "ff02::1"]:
            self.assertFalse(safe_fetch.is_public_ip(ip), ip)


class FakeResponse:
    def __init__(self, status, headers=None, body=b""):
        self.status_code, self.headers, self._body = status, headers or {}, body

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def aiter_bytes(self):
        for i in range(0, len(self._body), 1024):
            yield self._body[i:i + 1024]


class FakeClient:
    """Stands in for httpx.AsyncClient; records what was requested."""

    def __init__(self, routes):
        self.routes, self.requests, self.closed = routes, [], False

    def stream(self, method, url, headers=None, extensions=None):
        self.requests.append((url, dict(headers or {}), dict(extensions or {})))
        return self.routes[url]

    async def aclose(self):
        self.closed = True


class FetchTests(unittest.TestCase):
    def fetch(self, url, routes, **kw):
        client = FakeClient(routes)
        kw.setdefault("resolver", RESOLVE)
        result = asyncio.run(safe_fetch.fetch_html(url, client_factory=lambda t: client, **kw))
        return result, client

    def test_request_is_pinned_to_the_validated_ip_with_original_host(self):
        body, client = self.fetch("https://example.com/a?x=1", {
            "https://93.184.216.34/a?x=1": FakeResponse(200, body=b"<html>ok</html>")})
        self.assertEqual(body, b"<html>ok</html>")
        url, headers, ext = client.requests[0]
        self.assertEqual(headers["Host"], "example.com")
        self.assertEqual(ext["sni_hostname"], "example.com")
        self.assertTrue(client.closed)

    def test_redirects_are_followed_and_revalidated(self):
        body, client = self.fetch("http://example.com/start", {
            "http://93.184.216.34/start": FakeResponse(302, {"location": "/next"}),
            "http://93.184.216.34/next": FakeResponse(200, body=b"done")})
        self.assertEqual(body, b"done")

    def test_redirect_to_internal_address_is_blocked(self):
        for target in ["http://169.254.169.254/latest/meta-data/", "http://internal.test/", "http://localhost/",
                       "http://example.com:8080/", "file:///etc/passwd"]:
            client = FakeClient({"http://93.184.216.34/": FakeResponse(302, {"location": target})})
            with self.assertRaises(UnsafeURL, msg=target):
                asyncio.run(safe_fetch.fetch_html("http://example.com/", resolver=RESOLVE,
                                                  client_factory=lambda t: client))
            self.assertEqual(len(client.requests), 1, target)

    def test_redirect_loop_is_capped(self):
        client = FakeClient({"http://93.184.216.34/": FakeResponse(302, {"location": "/"})})
        with self.assertRaises(UnsafeURL):
            asyncio.run(safe_fetch.fetch_html("http://example.com/", resolver=RESOLVE, client_factory=lambda t: client))
        self.assertEqual(len(client.requests), safe_fetch.MAX_REDIRECTS + 1)

    def test_response_size_is_capped(self):
        with self.assertRaises(UnsafeURL):
            self.fetch("http://example.com/", {"http://93.184.216.34/": FakeResponse(200, body=b"x" * 5000)}, max_bytes=4096)
        with self.assertRaises(UnsafeURL):
            self.fetch("http://example.com/", {"http://93.184.216.34/": FakeResponse(
                200, {"content-length": "999999"}, b"x")}, max_bytes=4096)

    def test_private_target_never_reaches_the_client(self):
        client = FakeClient({})
        with self.assertRaises(UnsafeURL):
            asyncio.run(safe_fetch.fetch_html("http://127.0.0.1/", resolver=RESOLVE, client_factory=lambda t: client))
        self.assertEqual(client.requests, [])

    def test_timeout_is_enforced(self):
        class Slow(FakeResponse):
            async def __aenter__(self):
                await asyncio.sleep(5)
                return self
        with self.assertRaises(UnsafeURL):
            self.fetch("http://example.com/", {"http://93.184.216.34/": Slow(200)}, timeout=0.05)


if __name__ == "__main__":
    unittest.main()
