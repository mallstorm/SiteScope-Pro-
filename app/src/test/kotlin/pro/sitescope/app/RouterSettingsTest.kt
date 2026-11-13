package pro.sitescope.app

import android.app.Application
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RouterSettingsTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private fun config(base: String, kind: String = "routeros") = RouterStore.Config().apply { this.base = base; this.kind = kind; allowHttp = true }
    private val guard = RouterSettings.Guard { }
    private fun preview(c: RouterStore.Config, target: String = "new-router") = RouterApiClient(null).use { RouterSettings.preview(it, c, "owner", "owner-secret", "home", target, false, guard) }
    private fun apply(c: RouterStore.Config, p: RouterSettings.Plan, journal: RouterSettings.Journal = RouterChangeJournal.writer(app, "Дом")) = RouterApiClient(null).use { RouterSettings.apply(it, c, "owner", "owner-secret", "home", p, guard, journal) }
    private fun server(name: AtomicReference<String>, journalReady: AtomicBoolean? = null, failVerify: Boolean = false) = RouterApiTest.Server { r ->
        assertEquals("/rest/system/identity" , r.path.removeSuffix("/set"))
        if (r.method == "POST") {
            assertTrue(journalReady?.get() ?: true); val body = JSONObject(r.body); assertEquals(setOf("name"), body.keys().asSequence().toSet()); name.set(body.getString("name")); 200 to "[]"
        } else if (failVerify && name.get() == "new-router") 200 to "malformed"
        else 200 to JSONObject().put("name", name.get()).toString()
    }
    @Test fun previewIsReadOnlyAndApplyRequiresJournalBeforePostAndVerifiesReadback() {
        val current = AtomicReference("old-router"); val ready = AtomicBoolean(false)
        server(current, ready).use { s ->
            val c = config(s.base); val p = preview(c); assertTrue(s.requests.all { it.method == "GET" }); assertEquals("old-router", p.before)
            var checked = false
            apply(c, p, object : RouterSettings.Journal {
                override fun beforeWrite(plan: RouterSettings.Plan) { ready.set(true) }
                override fun applied(plan: RouterSettings.Plan) { checked = true; assertEquals("new-router", current.get()) }
            })
            assertEquals("new-router", current.get()); assertTrue(checked); assertEquals(1, s.requests.count { it.method == "POST" }); assertEquals(4, s.requests.size)
        }
    }
    @Test fun externalChangeAfterPreviewPreventsAnyWrite() {
        val current = AtomicReference("old-router")
        server(current).use { s -> val c = config(s.base); val p = preview(c); current.set("other-admin"); assertThrows(IllegalArgumentException::class.java) { apply(c, p) }; assertTrue(s.requests.all { it.method == "GET" }); assertTrue(RouterChangeJournal.list(app).isEmpty()) }
    }
    @Test fun journalFailurePreventsPostAndDoesNotLeakCredentials() {
        val current = AtomicReference("old-router")
        server(current).use { s -> val c = config(s.base); val p = preview(c); assertThrows(IOException::class.java) { apply(c, p, object : RouterSettings.Journal { override fun beforeWrite(plan: RouterSettings.Plan) { throw IOException("disk unavailable") }; override fun applied(plan: RouterSettings.Plan) { fail() } }) }; assertEquals("old-router", current.get()); assertTrue(s.requests.all { it.method == "GET" }) }
    }
    @Test fun lostVerificationLeavesDurableUnknownOutcomeInsteadOfClaimingNothingChanged() {
        val current = AtomicReference("old-router")
        server(current, failVerify = true).use { s -> val c = config(s.base); val p = preview(c); assertThrows(Exception::class.java) { apply(c, p) }; assertEquals("new-router", current.get()); val record = RouterChangeJournal.list(app).single(); assertEquals("unverified", record.getString("state")); assertEquals("old-router", record.getString("before")); assertFalse(record.toString().contains("owner-secret")); assertFalse(AppDatabase.get(app).exportPortable().toString().contains("router_changes_v1")) }
    }
    @Test fun restoreIsAnotherReviewedOperationAndUsesFreshBeforeValue() {
        val current = AtomicReference("old-router")
        server(current).use { s ->
            val c = config(s.base); apply(c, preview(c)); val original = RouterChangeJournal.list(app).single()
            val undo = RouterApiClient(null).use { RouterSettings.preview(it, c, "owner", "owner-secret", "home", original.getString("before"), true, guard) }
            assertEquals(original.getString("after"), undo.before); apply(c, undo); assertEquals("old-router", current.get()); assertEquals(2, RouterChangeJournal.list(app).size)
        }
    }
    @Test fun expiredOrOtherNetworkPlanDoesNotEvenContactRouter() {
        val current = AtomicReference("old-router")
        server(current).use { s ->
            val c = config(s.base); val p = preview(c); val count = s.requests.size
            for (invalid in listOf(RouterSettings.Plan(p.endpoint,p.kind,"other",p.section,p.before,p.after,p.time), RouterSettings.Plan(p.endpoint,p.kind,p.scope,p.section,p.before,p.after,0))) assertThrows(IllegalArgumentException::class.java) { apply(c, invalid) }
            assertEquals(count, s.requests.size)
        }
    }
    @Test fun refusesUnsupportedNameSyntaxAndNoOpWithoutPost() {
        for (invalid in listOf("", "-bad", "a b", "bad\nname", "a".repeat(64), "https://host")) assertThrows(IllegalArgumentException::class.java) { RouterSettings.newName(invalid) }
        server(AtomicReference("new-router")).use { s -> assertThrows(IllegalArgumentException::class.java) { preview(config(s.base)) }; assertTrue(s.requests.all { it.method == "GET" }) }
    }
    @Test fun changedNetworkAfterJournalingStopsPostAndKeepsUnverifiedEvidence() {
        server(AtomicReference("old-router")).use { s ->
            val c=config(s.base);val p=preview(c);var calls=0
            val changing=RouterSettings.Guard { calls++;if(calls==3)throw IllegalArgumentException("network changed") }
            assertThrows(Exception::class.java) { RouterApiClient(null).use { RouterSettings.apply(it,c,"owner","owner-secret","home",p,changing,RouterChangeJournal.writer(app,"Дом")) } }
            assertTrue(s.requests.all { it.method=="GET" });assertEquals("unverified",RouterChangeJournal.list(app).single().getString("state"));RouterChangeJournal.clear(app)
        }
    }
    @Test fun activeJournalCannotBeClearedAndSecondWriterCannotStart() {
        server(AtomicReference("old-router")).use { s ->
            val c=config(s.base);val p=preview(c);val delegate=RouterChangeJournal.writer(app,"Дом")
            apply(c,p,object: RouterSettings.Journal {
                override fun beforeWrite(plan: RouterSettings.Plan) { delegate.beforeWrite(plan);assertThrows(Exception::class.java) { RouterChangeJournal.clear(app) };assertThrows(IllegalArgumentException::class.java) { apply(c,p) } }
                override fun applied(plan: RouterSettings.Plan) { delegate.applied(plan) }
                override fun finished() { delegate.finished() }
            })
            assertEquals(1,s.requests.count { it.method=="POST" });RouterChangeJournal.clear(app);assertTrue(RouterChangeJournal.list(app).isEmpty())
        }
    }
    @Test fun journalCapacityBlocksWriteInsteadOfDroppingRecoveryValues() {
        server(AtomicReference("old-router")).use { s ->
            val c=config(s.base);val p=preview(c)
            repeat(100) { val writer=RouterChangeJournal.writer(app,"Дом");writer.beforeWrite(p);writer.finished() }
            assertThrows(Exception::class.java) { apply(c,p) };assertTrue(s.requests.all { it.method=="GET" });assertEquals(100,RouterChangeJournal.list(app).size);RouterChangeJournal.clear(app)
        }
    }
    @Test fun cancelledClientCannotStartWrite() {
        server(AtomicReference("old-router")).use { s ->
            val c=config(s.base);val p=preview(c);val client=RouterApiClient(null);client.close()
            assertThrows(Exception::class.java) { RouterSettings.apply(client,c,"owner","owner-secret","home",p,guard,RouterChangeJournal.writer(app,"Дом")) };assertTrue(s.requests.all { it.method=="GET" });assertTrue(RouterChangeJournal.list(app).isEmpty())
        }
    }
    private fun openwrtTest(drift: Boolean = false, denyConfirm: Boolean = false, pending: Boolean = false): Pair<List<String>, List<JSONObject>> {
        var saved = "old-router"; var staged: String? = null; val methods = mutableListOf<String>(); val args = mutableListOf<JSONObject>(); val token = "abcdef0123456789abcdef0123456789"
        RouterApiTest.Server { r ->
            assertEquals("/ubus", r.path); assertEquals("POST", r.method)
            val params = JSONObject(r.body).getJSONArray("params"); val key = params.getString(1) + "." + params.getString(2); val a = params.getJSONObject(3); methods.add(key); args.add(a)
            if (key != "session.login") assertEquals(token, params.getString(0))
            var code = 0
            val data = when(key) {
                "session.login" -> JSONObject().put("ubus_rpc_session", token)
                "session.destroy" -> { staged = null; JSONObject() }
                "uci.get" -> JSONObject().put("values", JSONObject().put("cfg01", JSONObject().put("hostname", staged ?: saved)))
                "uci.changes" -> {
                    val changes = JSONObject()
                    if (staged != null) changes.put("system", JSONArray().put(JSONArray().put("set").put("cfg01").put(if(drift) "timezone" else "hostname").put(staged)))
                    else if (pending) changes.put("wireless", JSONArray().put(JSONArray().put("set")))
                    JSONObject().put("changes", changes)
                }
                "uci.set" -> { assertEquals("system", a.getString("config")); assertEquals("cfg01", a.getString("section")); assertEquals(setOf("hostname"), a.getJSONObject("values").keys().asSequence().toSet()); staged = a.getJSONObject("values").getString("hostname"); JSONObject() }
                "uci.apply" -> { assertTrue(a.getBoolean("rollback")); assertEquals(60, a.getInt("timeout")); saved = staged!!; staged = null; JSONObject() }
                "uci.confirm" -> { if(denyConfirm)code=6; JSONObject() }
                else -> error("Unexpected operation $key")
            }
            200 to JSONObject().put("result", JSONArray().put(code).put(data)).toString()
        }.use { s ->
            val c = config(s.base, "openwrt"); val p = preview(c)
            if(drift || denyConfirm || pending) assertThrows(Exception::class.java) { apply(c,p) } else { apply(c,p); assertEquals("new-router", saved); assertEquals("applied", RouterChangeJournal.list(app).single().getString("state")) }
            assertTrue(s.failures.toString(), s.failures.isEmpty())
        }
        return methods to args
    }
    @Test fun openwrtAppliesOnlyHostnameWithRollbackTimerAndConfirmAfterReadback() {
        val (methods, _) = openwrtTest(); assertEquals(1, methods.count { it == "uci.set" }); assertEquals(1, methods.count { it == "uci.apply" }); val i=methods.indexOf("uci.apply"); assertEquals(listOf("uci.get","uci.confirm","uci.get","session.destroy"), methods.drop(i+1)); assertFalse(methods.contains("uci.commit")); assertFalse(methods.contains("uci.reload_config"))
    }
    @Test fun openwrtRejectsUnexpectedDeltaBeforeApplyAndDoesNotConfirm() {
        val (methods, _) = openwrtTest(drift=true); assertFalse(methods.contains("uci.apply")); assertFalse(methods.contains("uci.confirm")); assertEquals("session.destroy", methods.last()); assertEquals("unverified", RouterChangeJournal.list(app).single().getString("state"))
    }
    @Test fun openwrtPendingChangesPreventSetAndApply() {
        val (methods, _) = openwrtTest(pending=true); assertFalse(methods.contains("uci.set")); assertFalse(methods.contains("uci.apply"))
    }
    @Test fun openwrtFailedConfirmIsUnknownAndKeepsRecoveryRecord() {
        val (methods, _) = openwrtTest(denyConfirm=true); assertEquals("session.destroy", methods.last()); assertEquals("unverified", RouterChangeJournal.list(app).single().getString("state"))
    }
}
