package pro.sitescope.app.radio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pro.sitescope.app.ui.tones
import kotlin.math.*

@Composable
internal fun signalColor(dbm: Int?, wifi: Boolean = false): Color = when {
    dbm == null -> MaterialTheme.colorScheme.outline
    dbm >= if (wifi) -67 else -95 -> tones.good
    dbm >= if (wifi) -75 else -110 -> tones.warn
    else -> tones.bad
}

internal fun signalQuality(dbm: Int?, wifi: Boolean) = when {
    dbm == null -> "Нет измерения"
    dbm >= if (wifi) -55 else -80 -> "Отличный сигнал"
    dbm >= if (wifi) -67 else -95 -> "Хороший сигнал"
    dbm >= if (wifi) -75 else -110 -> "Слабый сигнал"
    else -> "Очень слабый сигнал"
}

/** The scale changes with the source. An absent modem measurement never becomes zero. */
@Composable
internal fun SignalGauge(dbm: Int?, wifi: Boolean, caption: String) {
    val colors = listOf(tones.bad, tones.warn, tones.good)
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val needle = MaterialTheme.colorScheme.primary
    val low = if (wifi) -100 else -140
    val high = if (wifi) -30 else -43
    Box(Modifier.fillMaxWidth().aspectRatio(1.18f).semantics {
        contentDescription = "$caption: ${dbm?.let { "$it dBm" } ?: "нет измерения"}"
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height * .59f)
            val radius = min(size.width * .39f, size.height * .48f)
            fun angle(v: Float) = Math.toRadians((150f + 240f * ((v-low)/(high-low)).coerceIn(0f,1f)).toDouble())
            fun at(a: Double, r: Float) = c + Offset(cos(a).toFloat()*r, sin(a).toFloat()*r)
            val bounds = Size(radius*2, radius*2)
            val top = c - Offset(radius,radius)
            val cuts = listOf(low, if(wifi) -75 else -110, if(wifi) -67 else -95, high)
            for(i in 0..2) {
                val start = 150f + 240f*(cuts[i]-low)/(high-low)
                val sweep = 240f*(cuts[i+1]-cuts[i])/(high-low)
                drawArc(colors[i],start,sweep-1f,false,top,bounds,style=Stroke(9.dp.toPx(),cap=StrokeCap.Round))
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=ink.toArgb(); textSize=11.dp.toPx(); textAlign=android.graphics.Paint.Align.CENTER
            }
            for(v in low..high step 5) {
                val a = angle(v.toFloat())
                drawLine(grid,at(a,radius+9.dp.toPx()),at(a,radius+if(v%10==0) 18.dp.toPx() else 13.dp.toPx()),1.dp.toPx())
                if(v%10==0) {
                    val p=at(a,radius+29.dp.toPx())
                    drawContext.canvas.nativeCanvas.drawText(v.toString(),p.x,p.y+4.dp.toPx(),paint)
                }
            }
            drawCircle(needle.copy(alpha=.05f),radius*.87f,c)
            if(dbm!=null) {
                val a=angle(dbm.toFloat())
                val side=Offset(-sin(a).toFloat(),cos(a).toFloat())*5.dp.toPx()
                val tip=at(a,radius*.88f)
                val path=Path().apply {moveTo(tip.x,tip.y);lineTo(c.x+side.x,c.y+side.y);lineTo(c.x-side.x,c.y-side.y);close()}
                drawPath(path,needle)
            }
            drawCircle(ink,8.dp.toPx(),c)
            drawCircle(needle,4.dp.toPx(),c)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(dbm?.let { "$it dBm" } ?: "— dBm",style=MaterialTheme.typography.headlineLarge)
            Text(signalQuality(dbm,wifi),color=signalColor(dbm,wifi),style=MaterialTheme.typography.labelLarge)
            Text(caption,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MetricGrid(metrics: List<Pair<String,String>>, columns: Int = 3) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        metrics.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                row.forEach { (label,value) -> Column(Modifier.weight(1f)) {
                    Text(value,style=MaterialTheme.typography.titleSmall)
                    Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" },Modifier.weight(1.25f),style=MaterialTheme.typography.bodyMedium,textAlign=androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Relative times from actual modem measurements; stale sources stay visible without synthetic points. */
@Composable
internal fun SignalChart(series: Map<String,List<Pair<Long,Int>>>, modifier: Modifier = Modifier.height(280.dp)) {
    val palette=listOf(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.tertiary,tones.warn,tones.good)
    val ink=MaterialTheme.colorScheme.onSurfaceVariant
    val grid=MaterialTheme.colorScheme.outlineVariant
    Column {
        series.keys.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            row.forEach { key -> Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(palette[series.keys.indexOf(key)%palette.size]))
                Spacer(Modifier.width(5.dp));Text(key,style=MaterialTheme.typography.labelSmall)
            } }
        } }
        Canvas(Modifier.fillMaxWidth().then(modifier).semantics {contentDescription="График уровня сигнала в dBm"}) {
            val left=36.dp.toPx();val right=size.width-8.dp.toPx();val bottom=size.height-22.dp.toPx()
            fun y(value:Int) = bottom-(value+150).coerceIn(0,125)/125f*(bottom-8.dp.toPx())
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {color=ink.toArgb();textSize=10.dp.toPx()}
            for(v in -140..-30 step 10) {
                drawLine(grid,Offset(left,y(v)),Offset(right,y(v)),1f)
                drawContext.canvas.nativeCanvas.drawText(v.toString(),0f,y(v)+4.dp.toPx(),paint)
            }
            val samples=series.values.flatten()
            val newest=samples.maxOfOrNull { it.first } ?: 0L
            val oldest=samples.minOfOrNull { it.first } ?: newest
            val span=(newest-oldest).coerceAtLeast(10_000)
            fun x(time:Long)=left+(right-left)*(1f-(newest-time).toFloat()/span)
            series.entries.forEachIndexed { index,(_,points) ->
                val color=palette[index%palette.size]
                for(i in 1 until points.size) drawLine(color,Offset(x(points[i-1].first),y(points[i-1].second)),Offset(x(points[i].first),y(points[i].second)),2.dp.toPx())
                points.lastOrNull()?.let {drawCircle(color,3.dp.toPx(),Offset(x(it.first),y(it.second)))}
            }
            drawContext.canvas.nativeCanvas.drawText("−${span/1000} с",left,size.height,paint)
            paint.textAlign=android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("последний замер",right,size.height,paint)
        }
        if(series.isEmpty()) Text("История появится после первого измерения.",style=MaterialTheme.typography.bodySmall)
    }
}
