from __future__ import annotations
import ipaddress, socket
from urllib.parse import urlparse

class UnsafeTarget(ValueError):
    pass

def validate_public_url(url: str) -> None:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise UnsafeTarget("Разрешены только публичные HTTP/HTTPS URL")
    host = parsed.hostname.rstrip(".").lower()
    if host in {"localhost", "localhost.localdomain"}:
        raise UnsafeTarget("Локальные адреса запрещены")
    try:
        infos = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM)
    except socket.gaierror as e:
        raise UnsafeTarget(f"DNS не разрешён: {e}") from e
    if not infos:
        raise UnsafeTarget("DNS не вернул адресов")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if any((ip.is_private, ip.is_loopback, ip.is_link_local, ip.is_multicast, ip.is_reserved, ip.is_unspecified)):
            raise UnsafeTarget(f"Небезопасный адрес назначения: {ip}")
