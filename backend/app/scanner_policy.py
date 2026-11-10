from __future__ import annotations

import ipaddress
import re

STANDARD_MAX_HOSTS = 4096
STANDARD_MAX_PORTS = 4096
STANDARD_MAX_PROBES = 2_000_000
EXTENDED_MAX_HOSTS = 65_536
EXTENDED_MAX_PORTS = 65_535
EXTENDED_MAX_PROBES = 10_000_000
MAX_CONCURRENCY = 64


def expand_targets(value: str, authorized: bool, extended_private: bool = False) -> list[str]:
    if not authorized:
        raise ValueError("Требуется подтверждение владения или письменного разрешения")
    max_hosts = EXTENDED_MAX_HOSTS if extended_private else STANDARD_MAX_HOSTS
    tokens = [x for x in re.split(r"[,;\s]+", value.strip()) if x]
    if not tokens:
        raise ValueError("Цель сканирования не указана")
    output: list[str] = []
    seen: set[str] = set()
    for token in tokens:
        rows = _expand_target_token(token, max_hosts)
        for row in rows:
            if row not in seen:
                seen.add(row)
                output.append(row)
                if len(output) > max_hosts:
                    raise ValueError(f"Диапазон превышает лимит выбранного режима: {max_hosts} узлов")
    return output


def parse_ports_expression(value: str, extended_private: bool = False) -> list[int]:
    max_ports = EXTENDED_MAX_PORTS if extended_private else STANDARD_MAX_PORTS
    tokens = [x for x in re.split(r"[,;\s]+", value.strip()) if x]
    if not tokens:
        raise ValueError("Список портов не указан")
    ports: set[int] = set()
    aliases = {
        "web": "80,443,8080,8443",
        "remote": "22,3389,3390,5900",
        "mail": "25,110,143,465,587,993,995",
    }
    expanded_tokens: list[str] = []
    for token in tokens:
        expanded_tokens.extend(aliases.get(token.lower(), token).split(","))
    for token in expanded_tokens:
        if "-" in token:
            parts = token.split("-", 1)
            start, end = _port(parts[0]), _port(parts[1])
            if end < start:
                raise ValueError(f"Конец диапазона меньше начала: {token}")
            if end - start + 1 > max_ports:
                raise ValueError(f"Диапазон портов превышает {max_ports}")
            ports.update(range(start, end + 1))
        else:
            ports.add(_port(token))
        if len(ports) > max_ports:
            raise ValueError(f"Слишком много портов: максимум {max_ports}")
    return sorted(ports)


def validate_scan_budget(targets: list[str], ports: list[int], extended_private: bool = False) -> int:
    max_probes = EXTENDED_MAX_PROBES if extended_private else STANDARD_MAX_PROBES
    total = len(targets) * len(ports)
    if total > max_probes:
        raise ValueError(f"Задание содержит {total} проверок; предел выбранного режима — {max_probes}")
    exceeds_standard = (
        len(targets) > STANDARD_MAX_HOSTS
        or len(ports) > STANDARD_MAX_PORTS
        or total > STANDARD_MAX_PROBES
    )
    if extended_private and exceeds_standard and not all_targets_private_or_local(targets):
        raise ValueError(
            "Расширенный объём разрешён только для числовых приватных/локальных IP-адресов"
        )
    return total


def all_targets_private_or_local(targets: list[str]) -> bool:
    if not targets:
        return False
    for target in targets:
        try:
            address = ipaddress.ip_address(target)
        except ValueError:
            return False
        allowed = address.is_loopback or address.is_link_local
        if address.version == 4:
            allowed = allowed or any(
                address in network
                for network in (
                    ipaddress.ip_network("10.0.0.0/8"),
                    ipaddress.ip_network("172.16.0.0/12"),
                    ipaddress.ip_network("192.168.0.0/16"),
                    ipaddress.ip_network("100.64.0.0/10"),
                )
            )
        else:
            allowed = allowed or address in ipaddress.ip_network("fc00::/7")
        if not allowed:
            return False
    return True


def _port(value: str) -> int:
    try:
        port = int(value)
    except ValueError as error:
        raise ValueError(f"Некорректный порт: {value}") from error
    if port < 1 or port > 65535:
        raise ValueError(f"Некорректный порт: {value}")
    return port


def _expand_target_token(token: str, max_hosts: int) -> list[str]:
    if token.endswith(".*"):
        prefix = token[:-1]
        if not re.fullmatch(r"(?:\d{1,3}\.){3}", prefix):
            raise ValueError("Поддерживается wildcard вида 192.168.1.*")
        rows = [f"{prefix}{index}" for index in range(1, 255)]
        for row in rows:
            ipaddress.ip_address(row)
        return rows
    if "/" in token:
        address, mask = token.split("/", 1)
        if "." in mask:
            prefix = ipaddress.IPv4Network(f"0.0.0.0/{mask}").prefixlen
            token = f"{address}/{prefix}"
        network = ipaddress.ip_network(token, strict=False)
        if network.version != 4:
            raise ValueError("CIDR мобильного сканера поддерживает IPv4")
        count = max(1, int(network.num_addresses) - (2 if network.prefixlen <= 30 else 0))
        if count > max_hosts:
            raise ValueError(f"CIDR содержит {count} узлов; выбранный режим допускает {max_hosts}")
        return [str(item) for item in network.hosts()]
    if _looks_like_range(token):
        start_text, end_text = token.split("-", 1)
        if "." not in end_text:
            end_text = start_text.rsplit(".", 1)[0] + "." + end_text
        start, end = ipaddress.ip_address(start_text), ipaddress.ip_address(end_text)
        if start.version != 4 or end.version != 4:
            raise ValueError("Диапазоны поддерживают IPv4")
        if int(end) < int(start):
            raise ValueError("Конец IP-диапазона меньше начала")
        count = int(end) - int(start) + 1
        if count > max_hosts:
            raise ValueError(f"IP-диапазон превышает {max_hosts} узлов")
        return [str(ipaddress.ip_address(value)) for value in range(int(start), int(end) + 1)]
    try:
        return [str(ipaddress.ip_address(token))]
    except ValueError:
        if not re.fullmatch(r"(?i)[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?", token):
            raise ValueError(f"Некорректный домен: {token}")
        return [token.lower()]


def _looks_like_range(token: str) -> bool:
    return bool(re.fullmatch(r"(?:\d{1,3}\.){3}\d{1,3}-(?:(?:\d{1,3}\.){3})?\d{1,3}", token))
