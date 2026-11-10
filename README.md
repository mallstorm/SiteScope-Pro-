# SiteScope Pro

SiteScope Pro is an Android engineering workspace that combines three independent areas: SEO audit, IP engineering, and IPTV engineering. The application package is `pro.sitescope.app`, the minimum Android API is 28, and the current direct-build target is Android API 36.

This source tree contains the direct Java Android implementation, local backend tests, offline build scripts, protocol clients, Hosts Manager, and the embedded ARM64 Xray/HEV combined VPN implementation.

## Offline build

```bash
SITESCOPE_OFFLINE_ENV=/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64 \
  ./scripts/build-direct-release.sh
```

A signed build additionally requires an explicitly supplied PKCS#12 key through the `SITESCOPE_SIGNING_*` environment variables. Private signing material is not included in this source tree.

## Current release

`0.38.0-alpha36` fixes a native-runtime startup deadlock observed after a standalone Xray profile test. The UI preflight no longer starts libv2ray, startup no longer calls a native version query between HEV bootstrap and the bounded SOCKS probe, and all profile preflight operations are process-wide cancellable.

## 0.38.0-alpha36

Adds explicit Android VPN-slot coordination for switching from another VPN app. Before creating TUN, SiteScope verifies that it still owns VPN permission, waits for the previous VPN network to disappear, selects a stable validated non-VPN underlay, passes it to `Builder.setUnderlyingNetworks()`, and temporarily binds startup traffic to that network. The Hosts Manager now detects a foreign VPN, offers a shortcut to system VPN settings, and keeps the red cancel control on one line with the other bottom buttons.
