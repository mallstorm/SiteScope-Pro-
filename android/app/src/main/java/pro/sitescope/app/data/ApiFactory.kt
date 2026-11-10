package pro.sitescope.app.data
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
object ApiFactory { fun create(baseUrl:String="http://10.0.2.2:8080/"):SiteScopeApi = Retrofit.Builder().baseUrl(baseUrl).addConverterFactory(MoshiConverterFactory.create()).build().create(SiteScopeApi::class.java) }
