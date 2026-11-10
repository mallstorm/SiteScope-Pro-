from __future__ import annotations

import asyncio
import ipaddress
import socket
import ssl
import time
from dataclasses import asdict, dataclass

COMMON_PORTS = {
    21: "FTP", 22: "SSH", 25: "SMTP", 53: "DNS", 80: "HTTP", 110: "POP3",
    143: "IMAP", 443: "HTTPS", 445: "SMB", 465: "SMTPS", 587: "SMTP Submission",
    993: "IMAPS", 995: "POP3S", 1433: "MS SQL", 1521: "Oracle", 3306: "MySQL",
    3389: "RDP", 5432: "PostgreSQL", 5900: "VNC", 6379: "Redis", 8080: "HTTP-alt",
    8443: "HTTPS-alt", 9200: "Elasticsearch",
}


@dataclass
class PortResult:
    target: str
    ip: str
    port: int
    open: bool
    latency_ms: int
    service: str
    resource_type: str = "Сетевой узел"
    hostname: str = ""
    banner: str = ""
    tls_subject: str = ""
    evidence: str = ""
    confidence: str = "low"

    def dict(self):
        return asdict(self)


async def probe_port(host: str, port: int, timeout: float = 0.8) -> PortResult:
    started = time.perf_counter()
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
        peer = writer.get_extra_info("peername")
        ip = peer[0] if peer else host
        latency = int((time.perf_counter() - started) * 1000)
        banner = ""
        try:
            data = await asyncio.wait_for(reader.read(512), min(timeout, 0.25))
            banner = _printable(data)
        except Exception:
            pass
        writer.close()
        await writer.wait_closed()
    except Exception:
        return PortResult(host, host, port, False, int((time.perf_counter() - started) * 1000), COMMON_PORTS.get(port, "Unknown TCP"))

    service, evidence, confidence = _classify_banner(banner)
    tls_subject = ""
    if not service:
        rdp = await _probe_rdp(host, port, timeout)
        if rdp:
            service, evidence, confidence, banner = "RDP", "TPKT/X.224 RDP negotiation response", "high", rdp
    if not service:
        tls = await _probe_tls(host, port, timeout)
        if tls:
            service, tls_subject, banner = tls
            evidence, confidence = "Успешный TLS handshake", "high"
    if not service:
        http = await _probe_http(host, port, timeout)
        if http:
            service, banner, evidence, confidence = "HTTP", http, "Получен HTTP status line", "high"
    if not service:
        service = COMMON_PORTS.get(port, "Unknown TCP")
        evidence = "TCP connect подтверждён; протокол определён только по известному порту" if service != "Unknown TCP" else "TCP connect подтверждён; сигнатура протокола не найдена"
        confidence = "low"
    hostname = await _reverse(ip)
    return PortResult(
        target=host,
        ip=ip,
        port=port,
        open=True,
        latency_ms=max(1, latency),
        service=service,
        resource_type=_resource_type(service, ip, hostname),
        hostname=hostname,
        banner=banner[:360],
        tls_subject=tls_subject,
        evidence=evidence,
        confidence=confidence,
    )


async def scan(host: str, ports: list[int], authorized: bool = False, concurrency: int = 32, delay_ms: int = 10, timeout_ms: int = 800):
    if not authorized:
        raise ValueError("Требуется подтверждение владения или разрешения")
    semaphore = asyncio.Semaphore(max(1, min(concurrency, 64)))

    async def one(port: int):
        async with semaphore:
            if delay_ms:
                await asyncio.sleep(delay_ms / 1000)
            return await probe_port(host, port, max(0.25, min(timeout_ms / 1000, 3.0)))

    return [result.dict() for result in await asyncio.gather(*(one(port) for port in sorted(set(ports))))]


def dns_lookup(host: str):
    rows = []
    for family, _, _, canon, sockaddr in socket.getaddrinfo(host, None):
        ip = sockaddr[0]
        rows.append({"family": "IPv6" if family == socket.AF_INET6 else "IPv4", "address": ip, "canonical": canon})
    seen = []
    for row in rows:
        if row not in seen:
            seen.append(row)
    return seen


def _classify_banner(banner: str):
    upper = banner.upper().strip()
    if upper.startswith("SSH-"):
        return "SSH", "SSH identification banner", "high"
    if upper.startswith("RFB "):
        return "VNC", "RFB/VNC greeting", "high"
    if upper.startswith("220") and ("SMTP" in upper or "ESMTP" in upper or "MAIL" in upper):
        return "SMTP", "SMTP/ESMTP greeting", "high"
    if upper.startswith("220") and "FTP" in upper:
        return "FTP", "FTP greeting", "high"
    if upper.startswith("+OK"):
        return "POP3", "POP3 +OK greeting", "high"
    if upper.startswith("* OK") or "IMAP4" in upper:
        return "IMAP", "IMAP greeting", "high"
    return "", "", ""


async def _probe_http(host: str, port: int, timeout: float):
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
        writer.write(f"HEAD / HTTP/1.0\r\nHost: {host}\r\nUser-Agent: SiteScopePro/0.4\r\nConnection: close\r\n\r\n".encode())
        await writer.drain()
        data = await asyncio.wait_for(reader.read(1000), timeout)
        writer.close()
        await writer.wait_closed()
        text = data.decode("latin1", "replace").strip()
        if text.upper().startswith("HTTP/"):
            return " | ".join(line.strip() for line in text.splitlines()[:4])
    except Exception:
        return ""
    return ""


async def _probe_rdp(host: str, port: int, timeout: float):
    request = bytes.fromhex("030000130ee000000000000100080003000000")
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
        writer.write(request)
        await writer.drain()
        data = await asyncio.wait_for(reader.read(64), timeout)
        writer.close()
        await writer.wait_closed()
        if len(data) >= 7 and data[0:2] == b"\x03\x00" and data[5] in (0xD0, 0xF0):
            return data[:24].hex(" ").upper()
    except Exception:
        pass
    return ""


async def _probe_tls(host: str, port: int, timeout: float):
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port, ssl=context, server_hostname=host), timeout)
        ssl_object = writer.get_extra_info("ssl_object")
        certificate = ssl_object.getpeercert() if ssl_object else {}
        subject = str(certificate.get("subject", ""))
        writer.write(f"HEAD / HTTP/1.0\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode())
        await writer.drain()
        try:
            data = await asyncio.wait_for(reader.read(800), min(timeout, 0.6))
            text = data.decode("latin1", "replace").strip()
        except Exception:
            text = ""
        writer.close()
        await writer.wait_closed()
        if text.upper().startswith("HTTP/"):
            return "HTTPS", subject, " | ".join(text.splitlines()[:4])
        return "TLS", subject, subject
    except Exception:
        return None


async def _reverse(ip: str):
    try:
        hostname, _, _ = await asyncio.to_thread(socket.gethostbyaddr, ip)
        return hostname if hostname != ip else ""
    except Exception:
        return ""


def _resource_type(service: str, ip: str, hostname: str):
    upper = service.upper()
    try:
        public = not ipaddress.ip_address(ip).is_private
    except ValueError:
        public = True
    named_server = any(token in hostname.lower() for token in ("vps", "server", "cloud", "node", "host"))
    if upper in {"RDP", "SSH", "VNC"}:
        return "VPS / удалённый сервер" if public or named_server else "Сервер локальной сети"
    if upper in {"HTTP", "HTTPS", "TLS"}:
        return "Веб-ресурс / сервер"
    if any(token in upper for token in ("SMTP", "IMAP", "POP3")):
        return "Почтовый сервер"
    if upper in {"MYSQL", "POSTGRESQL", "MS SQL", "ORACLE"}:
        return "Сервер базы данных"
    return "Публичный TCP-ресурс" if public else "Сетевой узел"


def _printable(data: bytes):
    return "".join(chr(value) if value in (9, 10, 13) or 32 <= value < 127 else "." for value in data).replace("\r", " ").replace("\n", " | ").strip()
