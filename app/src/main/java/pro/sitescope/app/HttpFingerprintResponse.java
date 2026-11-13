package pro.sitescope.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded root-page observation. No redirects, cookies, scripts or authentication. */
public final class HttpFingerprintResponse {
    public static final int MAX_HEADERS = 8192;
    public static final int MAX_BYTES = 24576;
    public String status = "", server = "", realm = "", title = "";
    private HttpFingerprintResponse() { }

    public boolean isHttp() { return status.matches("HTTP/1\\.[01] [1-5][0-9]{2}(?: .*)?"); }

    public static HttpFingerprintResponse request(Socket socket, String host, int timeoutMs) throws Exception {
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("Некорректный Host");
        for (int i = 0; i < host.length(); i++) if (host.charAt(i) <= 32 || host.charAt(i) == 127)
            throw new IllegalArgumentException("Некорректный Host");
        String authority = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        if (socket.getPort() != 80 && socket.getPort() != 443) authority += ":" + socket.getPort();
        String request = "GET / HTTP/1.0\r\nHost: " + authority + "\r\nUser-Agent: SiteScopePro/1.0\r\nAccept: text/html,application/xhtml+xml\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
        socket.getOutputStream().flush();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(120, timeoutMs));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[2048];
        InputStream input = socket.getInputStream();
        while (bytes.size() < MAX_BYTES && !Thread.currentThread().isInterrupted()) {
            long remaining = deadline - System.nanoTime(); if (remaining <= 0) break;
            socket.setSoTimeout((int) Math.max(1, Math.min(1000, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining))));
            int n;
            try { n = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES - bytes.size())); }
            catch (java.io.IOException stopped) { break; }
            if (n <= 0) break;
            bytes.write(buffer, 0, n);
            byte[] data = bytes.toByteArray(); int end = headerEnd(data);
            if (end < 0 && bytes.size() > MAX_HEADERS || end > MAX_HEADERS) break;
            if (end >= 0) {
                String headers = new String(data, 0, end, StandardCharsets.ISO_8859_1);
                String length = header(headers, "Content-Length");
                try { if (!length.isEmpty() && data.length - end >= Long.parseLong(length)) break; } catch (NumberFormatException ignored) { }
            }
        }
        return parse(bytes.toByteArray());
    }

    public static HttpFingerprintResponse parse(byte[] raw) {
        HttpFingerprintResponse result = new HttpFingerprintResponse();
        if (raw == null || raw.length > MAX_BYTES) return result;
        int end = headerEnd(raw); if (end < 0 || end > MAX_HEADERS) return result;
        String headers = new String(raw, 0, end, StandardCharsets.ISO_8859_1);
        result.status = DeviceIdentity.text(headers.split("\r?\n", 2)[0]);
        if (!result.isHttp()) return new HttpFingerprintResponse();
        result.server = DeviceIdentity.text(header(headers, "Server"));
        String challenge = header(headers, "WWW-Authenticate");
        Matcher realm = Pattern.compile("\\brealm\\s*=\\s*(?:\"([^\"\\r\\n]{0,240})\"|([^,\\s]{1,240}))", Pattern.CASE_INSENSITIVE).matcher(challenge);
        if (realm.find()) result.realm = DeviceIdentity.text(realm.group(1) != null ? realm.group(1) : realm.group(2));
        // Error pages can mention a product unrelated to the endpoint. Use titles only on success.
        if (!result.status.startsWith("HTTP/1.0 2") && !result.status.startsWith("HTTP/1.1 2")) return result;
        String contentType = header(headers, "Content-Type").toLowerCase(Locale.US);
        String encoding = header(headers, "Content-Encoding");
        if (!encoding.isEmpty() && !encoding.equalsIgnoreCase("identity")) return result;
        if (!contentType.isEmpty() && !contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) return result;
        byte[] body = java.util.Arrays.copyOfRange(raw, end, raw.length);
        if (header(headers, "Transfer-Encoding").equalsIgnoreCase("chunked")) body = dechunk(body);
        Charset charset = StandardCharsets.UTF_8;
        Matcher charSet = Pattern.compile("charset\\s*=\\s*[\"']?([a-z0-9_-]{1,40})").matcher(contentType);
        if (charSet.find()) try { charset = Charset.forName(charSet.group(1)); } catch (Exception ignored) { }
        String html = new String(body, charset);
        result.title = documentTitle(html);
        return result;
    }

    private static String documentTitle(String html) {
        String lower = html.toLowerCase(Locale.US), found = ""; int position = 0, count = 0;
        while (position < html.length()) {
            int start = lower.indexOf('<', position); if (start < 0) break;
            if (lower.startsWith("<!--", start)) {
                int end = lower.indexOf("-->", start + 4); if (end < 0) break;
                position = end + 3; continue;
            }
            int end = start + 1; char quote = 0;
            for (; end < html.length() && end - start <= 512; end++) {
                char c = html.charAt(end);
                if (quote != 0) { if (c == quote) quote = 0; }
                else if (c == '\'' || c == '"') quote = c;
                else if (c == '>') break;
            }
            if (end >= html.length() || end - start > 512) break;
            String tag = lower.substring(start + 1, end).trim().split("[\\s/]", 2)[0];
            position = end + 1;
            if (tag.equals("script") || tag.equals("style") || tag.equals("textarea") || tag.equals("xmp") || tag.equals("noscript") || tag.equals("template")) {
                int close = closingTag(lower, tag, position); if (close < 0) break;
                // Templates may nest; decline titles rather than interpreting their content.
                if (tag.equals("template")) return "";
                int closeEnd = lower.indexOf('>', close); if (closeEnd < 0) break;
                position = closeEnd + 1;
            } else if (tag.equals("title")) {
                int close = closingTag(lower, "title", position);
                if (close < 0 || close - position > 512) return "";
                String text = html.substring(position, close);
                if (text.indexOf('<') >= 0) return "";
                if (++count > 1) return "";
                found = DeviceIdentity.text(decodeEntities(text)); position = lower.indexOf('>', close) + 1;
            }
        }
        return found;
    }

    private static int closingTag(String html, String tag, int position) {
        String needle = "</" + tag;
        int start = position;
        while ((start = html.indexOf(needle, start)) >= 0) {
            int end = start + needle.length();
            while (end < html.length() && Character.isWhitespace(html.charAt(end))) end++;
            if (end < html.length() && html.charAt(end) == '>') return start;
            start += needle.length();
        }
        return -1;
    }

    private static int headerEnd(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) if (b[i] == 13 && b[i+1] == 10 && b[i+2] == 13 && b[i+3] == 10) return i + 4;
        return -1;
    }

    private static String header(String headers, String name) {
        StringBuilder out = new StringBuilder();
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                if (out.length() > 0) out.append(" | "); out.append(line.substring(colon + 1).trim());
            }
        }
        return out.toString();
    }

    private static byte[] dechunk(byte[] input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); int position = 0;
        try {
            while (position < input.length) {
                int end = position;
                while (end + 1 < input.length && !(input[end] == 13 && input[end+1] == 10)) end++;
                if (end + 1 >= input.length || end - position > 32) break;
                String line = new String(input, position, end - position, StandardCharsets.US_ASCII).split(";", 2)[0].trim();
                int size = Integer.parseInt(line, 16); position = end + 2;
                if (size == 0) return out.toByteArray();
                if (size < 0 || size > input.length - position || size > MAX_BYTES - out.size()) return new byte[0];
                out.write(input, position, size); position += size;
                if (position + 1 >= input.length || input[position] != 13 || input[position+1] != 10) break;
                position += 2;
            }
        } catch (Exception invalid) { return new byte[0]; }
        return new byte[0];
    }

    private static String decodeEntities(String value) {
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|amp|quot|apos|lt|gt|nbsp);").matcher(value);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String key = m.group(1), replacement = m.group();
            try {
                if (key.startsWith("#")) {
                    int code = key.startsWith("#x") ? Integer.parseInt(key.substring(2), 16) : Integer.parseInt(key.substring(1));
                    if (Character.isValidCodePoint(code) && !(code >= 0xD800 && code <= 0xDFFF)) replacement = new String(Character.toChars(code));
                } else if (key.equals("amp")) replacement = "&";
                else if (key.equals("quot")) replacement = "\"";
                else if (key.equals("apos")) replacement = "'";
                else if (key.equals("lt")) replacement = "<";
                else if (key.equals("gt")) replacement = ">";
                else replacement = " ";
            } catch (Exception ignored) { }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out); return out.toString();
    }
}
