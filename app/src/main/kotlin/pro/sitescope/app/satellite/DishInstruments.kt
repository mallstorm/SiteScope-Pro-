package pro.sitescope.app.satellite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.*

@Composable
internal fun DishAngleTile(title:String,value:Double?,kind:Int,modifier:Modifier=Modifier) {
    val ink=MaterialTheme.colorScheme.primary
    ElevatedCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical=12.dp,horizontal=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(title,style=MaterialTheme.typography.labelMedium)
            Canvas(Modifier.size(64.dp).padding(8.dp)) {
                val r=size.minDimension*.43f
                val c=center
                when(kind) {
                    0->{
                        drawCircle(ink,r,c,style=Stroke(2.dp.toPx()))
                        value?.let {v->
                            val a=Math.toRadians(v)
                            val end=c+Offset((sin(a)*r).toFloat(),(-cos(a)*r).toFloat())
                            drawLine(ink,c,end,3.dp.toPx())
                        }
                        drawCircle(ink,3.dp.toPx(),c)
                    }
                    1->{
                        val o=Offset(size.width*.1f,size.height*.85f)
                        drawLine(ink,o,Offset(size.width*.95f,o.y),2.dp.toPx())
                        drawArc(ink,-90f,90f,false,Offset(o.x-r,o.y-r),Size(r*2,r*2),style=Stroke(1.dp.toPx()))
                        value?.let{v->
                            val a=Math.toRadians(v)
                            drawLine(ink,o,o+Offset((cos(a)*r*1.7).toFloat(),(-sin(a)*r*1.7).toFloat()),3.dp.toPx())
                        }
                    }
                    else->{
                        drawCircle(ink,r,c,style=Stroke(2.dp.toPx()))
                        rotate((value ?: 0.0).toFloat(),c) {
                            drawRoundRect(ink,Offset(c.x-r*.35f,c.y-r*.7f),Size(r*.7f,r*1.4f),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),style=Stroke(3.dp.toPx()))
                            drawLine(ink,Offset(c.x,c.y-r),Offset(c.x,c.y-r*.7f),2.dp.toPx())
                        }
                    }
                }
            }
            Text(degrees(value),style=MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
internal fun DishCompass(target:Double?,heading:Double?,hasLocation:Boolean) {
    val primary=MaterialTheme.colorScheme.primary
    val ink=MaterialTheme.colorScheme.onSurfaceVariant
    val grid=MaterialTheme.colorScheme.outlineVariant
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("Компас",style=MaterialTheme.typography.titleSmall)
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val c=center;val r=size.minDimension*.39f
                drawCircle(grid,r,c,style=Stroke(1.dp.toPx()))
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=ink.toArgb();textSize=12.dp.toPx();textAlign=android.graphics.Paint.Align.CENTER}
                fun at(a:Double,radius:Float)=c+Offset((sin(a)*radius).toFloat(),(-cos(a)*radius).toFloat())
                for(d in 0 until 360 step 15) {
                    val a=Math.toRadians(d-(heading ?: 0.0))
                    drawLine(grid,at(a,r),at(a,r-if(d%90==0)12.dp.toPx() else 5.dp.toPx()),1.dp.toPx())
                    if(d%90==0){val p=at(a,r+12.dp.toPx());drawContext.canvas.nativeCanvas.drawText(listOf("N","E","S","W")[d/90],p.x,p.y+4.dp.toPx(),paint)}
                }
                drawLine(ink,Offset(c.x,c.y-r*.9f),Offset(c.x,c.y-r*.65f),3.dp.toPx())
                if(heading!=null && target!=null) {
                    val a=Math.toRadians(wrapped(target-heading))
                    val tip=at(a,r*.76f)
                    val side=Offset(cos(a).toFloat(),sin(a).toFloat())*8.dp.toPx()
                    val path=Path().apply{moveTo(tip.x,tip.y);lineTo(c.x+side.x,c.y+side.y);lineTo(c.x-side.x,c.y-side.y);close()}
                    drawPath(path,primary)
                }
                drawCircle(primary,5.dp.toPx(),c)
            }
            Text(degrees(heading),style=MaterialTheme.typography.headlineMedium)
            Text(if(hasLocation)"Курс с учётом магнитного склонения" else "Магнитный курс · точка установки не задана",style=MaterialTheme.typography.labelSmall)
            val delta=if(target!=null && heading!=null)wrapped(target-heading) else null
            Text(when {
                heading==null->"Нет данных датчика направления"
                delta==null->"Выберите спутник и точку установки"
                abs(delta)<1->"Направление совмещено"
                delta>0->"Поверните вправо на ${degrees(abs(delta))}"
                else->"Поверните влево на ${degrees(abs(delta))}"
            },style=MaterialTheme.typography.bodyMedium,color=primary)
        }
    }
}
