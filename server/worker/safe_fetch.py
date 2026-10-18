# SSRF-safe page fetching for article jobs.
#
# The URL comes from an anonymous user, so the worker (which sits next to other
# services on the VPS) must never be talked into requesting internal addresses.
# Every hop (the first request and each redirect) is validated, and the request is
# sent to the IP address that was validated, not to a name that could resolve
# differently a moment later (DNS rebinding).

import asyncio
import ipaddress
import socket
from urllib.parse import urljoin, urlsplit

ALLOWED_SCHEMES = {"http": 80, "https": 443}
ALLOWED_PORTS = {80, 443}
MAX_REDIRECTS = 5
MAX_RESPONSE_BYTES = 10 * 1024 * 1024
USER_AGENT = "Mozilla/5.0 (compatible; PageDropBot/1.0; +https://github.com/)"

_BLOCKED_NETWORKS = [ipaddress.ip_network(n) for n in (
    "0.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16", "192.0.0.0/24", "198.18.0.0/15",
    "240.0.0.0/4",
    "fc00::/7", "fe80::/10", "64:ff9b::/96", "64:ff9b:1::/48", "2002::/16", "2001::/32",
)]


class UnsafeURL(ValueError):
    """The URL (or a redirect target) is not allowed to be fetched."""


def is_public_ip(ip) -> bool:
    """True only for globally routable unicast addresses."""
    if isinstance(ip, str):
        ip = ipaddress.ip_address(ip)
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    if (ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast
            or ip.is_reserved or ip.is_unspecified or not ip.is_global):
        return False
    return not any(ip in net for net in _BLOCKED_NETWORKS if net.version == ip.version)


def _strip_brackets(host: str) -> str:
    return host[1:-1] if host.startswith("[") and host.endswith("]") else host


def validate_url(url: str, resolver=socket.getaddrinfo):
    """Return (scheme, host, port, [validated ips]) or raise UnsafeURL.

    `resolver` has the signature of socket.getaddrinfo (injectable for tests).
    """
    if not isinstance(url, str) or len(url) > 2048:
        raise UnsafeURL("Invalid URL")
    try:
        parts = urlsplit(url.strip())
        scheme = parts.scheme.lower()
        host = parts.hostname
        port = parts.port
    except ValueError:
        raise UnsafeURL("Invalid URL")
    if scheme not in ALLOWED_SCHEMES:
        raise UnsafeURL("Only http/https URLs are supported")
    if parts.username is not None or parts.password is not None:
        raise UnsafeURL("URLs with credentials are not allowed")
    if not host:
        raise UnsafeURL("URL has no host")
    host = _strip_brackets(host).rstrip(".").lower()
    if port is None:
        port = ALLOWED_SCHEMES[scheme]
    if port not in ALLOWED_PORTS:
        raise UnsafeURL("Only ports 80 and 443 are allowed")
    if host == "localhost" or host.endswith(".localhost"):
        raise UnsafeURL("Host is not allowed")

    try:
        literal = ipaddress.ip_address(host.split("%")[0])
    except ValueError:
        literal = None
        # Decimal/hex/octal/short forms such as 2130706433 or 0x7f.1 that the resolver
        # would turn into an IPv4 address: never legitimate in an article link.
        try:
            socket.inet_aton(host)
        except OSError:
            pass
        else:
            raise UnsafeURL("Host is not allowed")

    if literal is not None:
        addrs = [literal]
    else:
        try:
            infos = resolver(host, port, type=socket.SOCK_STREAM)
        except OSError:
            raise UnsafeURL("Could not resolve host")
        addrs = []
        for info in infos:
            try:
                addrs.append(ipaddress.ip_address(info[4][0].split("%")[0]))
            except ValueError:
                raise UnsafeURL("Host is not allowed")
        if not addrs:
            raise UnsafeURL("Could not resolve host")

    # ANY non-public answer rejects the host, even if public ones are also returned.
    if not all(is_public_ip(a) for a in addrs):
        raise UnsafeURL("Host is not allowed")
    return scheme, host, port, [str(a) for a in addrs]


def _pinned_request_url(scheme: str, ip: str, port: int, url: str) -> str:
    parts = urlsplit(url)
    netloc = f"[{ip}]" if ":" in ip else ip
    if port != ALLOWED_SCHEMES[scheme]:
        netloc += f":{port}"
    path = parts.path or "/"
    return f"{scheme}://{netloc}{path}" + (f"?{parts.query}" if parts.query else "")


async def _fetch_one(client, url, scheme, host, port, ips, max_bytes):
    """One request pinned to a validated IP. Returns (status, location, body)."""
    last_error = None
    for ip in ips:
        request_url = _pinned_request_url(scheme, ip, port, url)
        headers = {"Host": host if port == ALLOWED_SCHEMES[scheme] else f"{host}:{port}",
                   "User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"}
        try:
            async with client.stream(
                "GET", request_url, headers=headers,
                extensions={"sni_hostname": host},  # TLS SNI and certificate check use the real name
            ) as response:
                if response.status_code in (301, 302, 303, 307, 308):
                    return response.status_code, response.headers.get("location"), b""
                if response.status_code >= 400:
                    raise UnsafeURL(f"Server returned HTTP {response.status_code}")
                declared = response.headers.get("content-length")
                if declared and declared.isdigit() and int(declared) > max_bytes:
                    raise UnsafeURL("Page is too large")
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    body.extend(chunk)
                    if len(body) > max_bytes:
                        raise UnsafeURL("Page is too large")
                return response.status_code, None, bytes(body)
        except UnsafeURL:
            raise
        except Exception as exc:  # connection failure: try the next validated address
            last_error = exc
    raise UnsafeURL(f"Failed to fetch URL ({type(last_error).__name__})")


async def _fetch_html(url, resolver, client_factory, max_bytes, timeout):
    client = client_factory(timeout)
    try:
        current = url
        for _ in range(MAX_REDIRECTS + 1):
            scheme, host, port, ips = await asyncio.to_thread(validate_url, current, resolver)
            status, location, body = await _fetch_one(client, current, scheme, host, port, ips, max_bytes)
            if location is None:
                return body
            current = urljoin(current, location)
        raise UnsafeURL("Too many redirects")
    finally:
        await client.aclose()


def _default_client_factory(timeout):
    import httpx
    return httpx.AsyncClient(follow_redirects=False, timeout=httpx.Timeout(timeout), trust_env=False)


async def fetch_html(
    url: str,
    timeout: float = 30,
    max_bytes: int = MAX_RESPONSE_BYTES,
    resolver=socket.getaddrinfo,
    client_factory=None,
) -> bytes:
    """Fetch a public web page as bytes, or raise UnsafeURL. The whole fetch is bounded by `timeout`."""
    try:
        return await asyncio.wait_for(
            _fetch_html(url, resolver, client_factory or _default_client_factory, max_bytes, timeout),
            timeout=timeout,
        )
    except asyncio.TimeoutError:
        raise UnsafeURL("Timed out fetching URL")
