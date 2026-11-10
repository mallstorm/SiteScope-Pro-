package pro.sitescope.app.ipintel;

import java.math.BigInteger;
import java.util.List;

public final class ProviderCoreHarness {
    public static void main(String[] args) {
        assertEquals("интертелеком", ProviderCore.normalizeProviderQuery("Інтертелеком"), "ukrainian normalization");
        assertEquals("интертелеком", ProviderCore.normalizeProviderQuery("Интертелеком"), "russian normalization");
        assertEquals("intertelecom", ProviderCore.normalizeProviderQuery("Inter Telecom"), "english normalization");
        List<String> aliases = ProviderCore.aliasCandidates("Intertelecom");
        assertTrue(aliases.contains("AS31343"), "Intertelecom alias");

        ProviderCore.CidrRange v4 = ProviderCore.range("192.0.2.15/22");
        assertEquals("192.0.0.0", v4.first, "IPv4 first");
        assertEquals("192.0.3.255", v4.last, "IPv4 last");
        assertEquals("1024", v4.count.toString(), "IPv4 count");
        assertTrue(v4.contains("192.0.2.15"), "IPv4 contains");

        ProviderCore.CidrRange v6 = ProviderCore.range("2001:db8::2/126");
        assertEquals("4", v6.count.toString(), "IPv6 count");
        assertTrue(v6.contains("2001:db8::3"), "IPv6 contains");

        ProviderCore.CidrRange all = ProviderCore.range("0.0.0.0/0");
        assertEquals(new BigInteger("4294967296").toString(), all.count.toString(), "IPv4 /0 count");
        System.out.println("PROVIDER_CORE_HARNESS_PASS");
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
