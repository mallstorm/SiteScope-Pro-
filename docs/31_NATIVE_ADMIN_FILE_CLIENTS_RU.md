# Этап 31 — встроенные SSH/SFTP/FTP/FTPS/WebDAV клиенты

## SSH
- host key verification;
- private known_hosts;
- password/key auth;
- PTY shell;
- manual command input.

## SFTP
- directory list;
- navigation;
- upload/download through SAF.

## FTP/FTPS
- passive data connections;
- MLSD/LIST;
- RETR/STOR;
- explicit and implicit TLS.

## WebDAV
- PROPFIND/GET/PUT;
- Basic Auth;
- system TLS trust.

## Security rules
Credentials are session-only. No brute force, password guessing, automatic command execution or certificate bypass is implemented.
