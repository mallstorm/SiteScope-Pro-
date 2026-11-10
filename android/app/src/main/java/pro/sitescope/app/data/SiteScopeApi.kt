package pro.sitescope.app.data
import retrofit2.http.Body
import retrofit2.http.POST
interface SiteScopeApi { @POST("api/v1/audit") suspend fun audit(@Body request:AuditRequest):AuditDto }
