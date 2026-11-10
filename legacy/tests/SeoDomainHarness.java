package pro.sitescope.app;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SeoDomainHarness {
    private static void name(DataOutputStream output, String name) throws Exception {
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes("UTF-8");
            output.writeByte(bytes.length);
            output.write(bytes);
        }
        output.writeByte(0);
    }

    private static byte[] dnsPacket() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeShort(0x1234);
        output.writeShort(0x81a0);
        output.writeShort(1);
        output.writeShort(3);
        output.writeShort(0);
        output.writeShort(0);
        name(output, "example.com");
        output.writeShort(1);
        output.writeShort(1);

        output.writeShort(0xc00c);
        output.writeShort(1);
        output.writeShort(1);
        output.writeInt(300);
        output.writeShort(4);
        output.write(new byte[]{93, (byte) 184, (byte) 216, 34});

        output.writeShort(0xc00c);
        output.writeShort(2);
        output.writeShort(1);
        output.writeInt(3600);
        ByteArrayOutputStream nsBytes = new ByteArrayOutputStream();
        DataOutputStream nsOut = new DataOutputStream(nsBytes);
        name(nsOut, "ns1.example.net");
        byte[] ns = nsBytes.toByteArray();
        output.writeShort(ns.length);
        output.write(ns);

        output.writeShort(0xc00c);
        output.writeShort(16);
        output.writeShort(1);
        output.writeInt(600);
        byte[] txt = "v=spf1 -all".getBytes("UTF-8");
        output.writeShort(txt.length + 1);
        output.writeByte(txt.length);
        output.write(txt);
        return bytes.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        Models.AuditResult result = new Models.AuditResult();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Server", "nginx");
        headers.put("Set-Cookie", "wordpress_logged_in=x; woocommerce_cart_hash=y");
        TechnologyAnalyzer.analyze(result, headers,
            "<meta name=\"generator\" content=\"WordPress 6.8\">" +
            "<script src=\"/wp-includes/js/jquery.js\"></script>" +
            "<link href=\"/wp-content/themes/x/style.css\">" +
            "<script src=\"https://www.googletagmanager.com/gtag/js\"></script>");
        if (!"WordPress".equals(result.cmsName) || result.cmsConfidence < 90) {
            throw new IllegalStateException("CMS detection failed: " + result.cmsName + " " + result.cmsConfidence);
        }
        boolean woo = false;
        boolean nginx = false;
        for (Models.TechnologyFinding finding : result.technologies) {
            if ("WooCommerce".equals(finding.name)) woo = true;
            if (finding.name.toLowerCase().contains("nginx")) nginx = true;
        }
        if (!woo || !nginx) throw new IllegalStateException("Technology evidence list incomplete");

        List<Models.DnsRecord> records = DnsAnalyzer.parsePacketForTest(dnsPacket(), 0x1234);
        boolean a = false;
        boolean ns = false;
        boolean spf = false;
        for (Models.DnsRecord record : records) {
            if ("A".equals(record.type) && "93.184.216.34".equals(record.value)) a = true;
            if ("NS".equals(record.type) && "ns1.example.net".equals(record.value)) ns = true;
            if ("TXT".equals(record.type) && record.value.startsWith("v=spf1")) spf = true;
        }
        if (!a || !ns || !spf) throw new IllegalStateException("DNS parser failed");
        if (!"example.co.uk".equals(DomainRdapClient.registrableDomain("www.shop.example.co.uk"))) {
            throw new IllegalStateException("Registrable-domain extraction failed");
        }
        System.out.println("SEO domain harness PASS: CMS=" + result.cmsName + " " + result.cmsConfidence + "% · DNS records=" + records.size());
    }
}
