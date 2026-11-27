package pro.sitescope.app.radio

data class Tower(val rat:String,val mcc:String,val mnc:String,val area:Long,val cid:Long,val pci:Int?,val channel:Int?,
    val lat:Double?,val lon:Double?,val accuracy:Int?,val name:String,val bearing:Int? = null) {
    fun clf() = listOf(rat,mcc,mnc,area,cid,pci,channel,lat,lon,accuracy,name.replace(';',',').replace('\n',' '),bearing).joinToString(";"){it?.toString().orEmpty()}
}

fun parseTower(line:String): Tower? {
    val clean=line.trim().removePrefix("\uFEFF")
    if(clean.isEmpty() || clean.startsWith("#")) return null
    val f=clean.split(';').map(String::trim)
    fun id(v:String)=if(v.startsWith("0x",true)) v.drop(2).toLongOrNull(16) else v.toLongOrNull()
    fun tower(rat:String,plmn:String,area:Long?,cid:Long?,name:String,lat:String="",lon:String="",pci:Int?=null,ch:Int?=null,acc:Int?=null,angle:Int?=null):Tower? {
        if(!plmn.matches(Regex("[0-9]{5,6}")) || area==null || area<0 || cid==null || cid !in 0..68719476735L) return null
        val y=lat.toDoubleOrNull();val x=lon.toDoubleOrNull()
        if(lat.isNotBlank() || lon.isNotBlank()) if(y==null || x==null || y !in -90.0..90.0 || x !in -180.0..180.0) return null
        return Tower(rat,plmn.take(3),plmn.drop(3),area,cid,pci,ch,y,x,acc,name.take(500),angle?.takeIf {it in 0..359})
    }
    if(f.size>=10 && f[0] in listOf("","G","L","N","C","W","T")) return tower(f[0],f[1]+f[2],id(f[3]),id(f[4]),f.getOrNull(10).orEmpty(),f[7],f[8],f[5].toIntOrNull(),f[6].toIntOrNull(),f[9].toIntOrNull(),f.getOrNull(11)?.toIntOrNull())
    if(f.size==9) {
        val cid=id(f[1]) ?: return null
        val rnc=id(f[3]) ?: 0
        return tower(if(rnc>0) "W" else "",f[0],id(f[2]),if(rnc>0) (rnc shl 16) or cid else cid,f[7],f[4],f[5])
    }
    val key=clean.substringBefore('\t')
    if(!clean.contains('\t')) return null
    val hex=key.length in 13..14
    if(!hex && key.length !in 15..16) return null
    val n=if(hex) 4 else 5
    val base=if(hex) 16 else 10
    return tower("",key.drop(2*n),key.substring(n,2*n).toLongOrNull(base),key.take(n).toLongOrNull(base),clean.substringAfter('\t'))
}

