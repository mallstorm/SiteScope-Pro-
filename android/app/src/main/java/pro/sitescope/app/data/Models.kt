package pro.sitescope.app.data

data class IssueDto(val code:String,val severity:String,val title:String,val why:String,val evidence:String?,val recommendation:String,val affected_url:String)
data class PageDto(val requested_url:String,val final_url:String,val status_code:Int,val elapsed_ms:Int,val title:String?,val description:String?,val issues:List<IssueDto>)
data class AuditDto(val target:String,val score:Int,val category_scores:Map<String,Int>,val issue_counts:Map<String,Int>,val pages:List<PageDto>,val issues:List<IssueDto>,val limitations:List<String>)
data class AuditRequest(val url:String,val max_pages:Int=25,val max_depth:Int=2)
