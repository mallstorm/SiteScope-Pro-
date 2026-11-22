package pro.sitescope.app.keys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SingBoxConfigTest {
    @Test fun hysteria2HasRealPasswordTlsAndObfs() {
        val json = JSONObject(SingBoxConfig.build(
            "hysteria2://pass123@h.example:443?sni=real.example&obfs=salamander&obfs-password=obfs123", 32001))
        val node = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("hysteria2", node.getString("type"))
        assertEquals("pass123", node.getString("password"))
        assertEquals("real.example", node.getJSONObject("tls").getString("server_name"))
        assertEquals("obfs123", node.getJSONObject("obfs").getString("password"))
        assertEquals(32001, json.getJSONArray("inbounds").getJSONObject(0).getInt("listen_port"))
        assertFalse(json.has("tun"))
    }

    @Test fun tuicPreservesUuidPasswordAndQuicTls() {
        val node = JSONObject(SingBoxConfig.build(
            "tuic://00000000-0000-4000-8000-000000000001@tuic.example:443?password=pw123&sni=cert.example",
            32002)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("tuic", node.getString("type"))
        assertEquals("pw123", node.getString("password"))
        assertEquals("00000000-0000-4000-8000-000000000001", node.getString("uuid"))
        assertEquals("cert.example", node.getJSONObject("tls").getString("server_name"))
    }

    @Test fun anyTlsIsIndependentOutbound() {
        val node = JSONObject(SingBoxConfig.build("anytls://secret@tls.example:443?sni=tls.example",
            32003)).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("anytls", node.getString("type"))
        assertEquals("secret", node.getString("password"))
    }

    @Test fun wireGuardConfBecomesEndpointWithPeer() {
        val ini = """
            [Interface]
            PrivateKey = privatefixturekey
            Address = 10.14.0.2/32
            DNS = 1.1.1.1
            [Peer]
            PublicKey = publicfixturekey
            AllowedIPs = 0.0.0.0/0
            Endpoint = wg.example:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val imported = SingBoxConfig.importProfile(ini)
        assertEquals("wireguard", imported.type)
        assertEquals("wg.example", imported.host)
        val json = JSONObject(SingBoxConfig.build(imported.link, 32004))
        assertTrue(json.has("endpoints"))
        val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("wireguard", endpoint.getString("type"))
        assertFalse(endpoint.getBoolean("system"))
        assertEquals("wg.example", endpoint.getJSONArray("peers").getJSONObject(0).getString("address"))
    }

    @Test fun openVpnInlineCaBecomesClientEndpoint() {
        val ovpn = """
            client
            proto udp
            remote vpn.example 1194
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val imported = SingBoxConfig.importProfile(ovpn)
        assertEquals("openvpn", imported.type)
        val endpoint = JSONObject(SingBoxConfig.build(imported.link, 32005))
            .getJSONArray("endpoints").getJSONObject(0)
        assertEquals("openvpn-client", endpoint.getString("type"))
        assertEquals("vpn.example", endpoint.getString("server"))
        assertTrue(endpoint.getJSONObject("tls").getJSONArray("certificate").length() > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteTuicCannotBeVerified() {
        SingBoxConfig.build("tuic://uuid@server.example:443", 32006)
    }

    @Test(expected = IllegalArgumentException::class)
    fun openVpnExternalCaDoesNotPretendToWork() {
        SingBoxConfig.build(SingBoxConfig.importProfile(
            "client\nremote example.com 1194\nca missing.crt").link, 32007)
    }
}
