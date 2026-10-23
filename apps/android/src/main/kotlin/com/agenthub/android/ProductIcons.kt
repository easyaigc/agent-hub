package com.agenthub.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

// App-owned vector geometry maps icon meanings without copying platform glyphs.
@Composable internal fun ProductIcon(name: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(20.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(1.7f * u, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x*u,y*u), Offset(xx*u,yy*u), 1.7f*u, StrokeCap.Round)
        fun path(vararg points: Float) { val p = Path(); p.moveTo(points[0]*u, points[1]*u); for (i in 2 until points.size step 2) p.lineTo(points[i]*u,points[i+1]*u); drawPath(p,color,style=stroke) }
        when(name) {
            "question" -> { drawRoundRect(color,Offset(2f*u,2f*u),Size(20f*u,16f*u),androidx.compose.ui.geometry.CornerRadius(3f*u),style=stroke); path(7f,18f,7f,22f,12f,18f); drawArc(color,190f,270f,false,Offset(9f*u,5f*u),Size(6f*u,6f*u),style=stroke); line(12f,10f,12f,12f); drawCircle(color,.8f*u,Offset(12f*u,15f*u)) }
            "shield" -> { path(12f,2f,3f,6f,4f,15f,7f,19f,12f,22f,17f,19f,20f,15f,21f,6f,12f,2f) }
            "bolt" -> path(14f,2f,4f,14f,11f,14f,9f,22f,21f,9f,13f,9f,14f,2f)
            "lightbulb" -> { drawArc(color,160f,220f,false,Offset(5f*u,2f*u),Size(14f*u,14f*u),style=stroke); path(5.5f,11f,9f,17f,15f,17f,18.5f,11f); line(9f,20f,15f,20f); line(11f,22f,13f,22f) }
            "tools" -> { for (point in listOf(Offset(5f,5f),Offset(19f,5f),Offset(5f,19f),Offset(19f,19f))) drawCircle(color,3f*u,point*u,style=stroke); line(8f,16f,16f,8f) }
            "command" -> { path(3f,5f,9f,12f,3f,19f); line(12f,19f,21f,19f) }
            "menu" -> { line(3f,7f,21f,7f); line(3f,12f,15f,12f); line(3f,17f,21f,17f) }
            "sidebar" -> { drawRoundRect(color,Offset(2f*u,4f*u),Size(20f*u,16f*u),androidx.compose.ui.geometry.CornerRadius(2f*u),style=stroke); line(9f,4f,9f,20f) }
            "plus" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "chevron" -> path(8f,5f,15f,12f,8f,19f)
            "down" -> path(6f,9f,12f,15f,18f,9f)
            "up" -> path(6f,15f,12f,9f,18f,15f)
            "check" -> path(4f,12f,9f,17f,20f,6f)
            "search" -> { drawCircle(color,7f*u,Offset(10f*u,10f*u),style=stroke); line(15f,15f,21f,21f) }
            "desktop" -> { drawRoundRect(color,Offset(2f*u,3f*u),Size(20f*u,14f*u),androidx.compose.ui.geometry.CornerRadius(2f*u),style=stroke); line(12f,17f,12f,21f); line(7f,21f,17f,21f) }
            "laptop" -> { drawRoundRect(color,Offset(4f*u,4f*u),Size(16f*u,13f*u),style=stroke); path(2f,20f,22f,20f) }
            "folder" -> path(3f,19f,3f,6f,10f,6f,12f,9f,21f,9f,21f,19f,3f,19f)
            "new" -> { path(19f,13f,19f,21f,3f,21f,3f,5f,11f,5f); path(9f,15f,10f,10f,19f,1f,23f,5f,14f,14f,9f,15f) }
            "send" -> { line(12f,20f,12f,4f); path(5f,11f,12f,4f,19f,11f) }
            "stop" -> drawRect(color,Offset(6f*u,6f*u),Size(12f*u,12f*u))
            "resume" -> { val p = Path(); p.moveTo(7f*u,4f*u); p.lineTo(21f*u,12f*u); p.lineTo(7f*u,20f*u); p.close(); drawPath(p,color) }
            "keyboard" -> { drawRoundRect(color,Offset(2f*u,3f*u),Size(20f*u,13f*u),style=stroke); for(y in listOf(7f,10f)) for(x in listOf(6f,10f,14f,18f)) drawCircle(color,.6f*u,Offset(x*u,y*u)); line(7f,13f,17f,13f); path(8f,19f,12f,22f,16f,19f) }
            "mic" -> { drawRoundRect(color,Offset(9f*u,2f*u),Size(6f*u,13f*u),androidx.compose.ui.geometry.CornerRadius(3f*u),style=stroke); path(5f,11f,5f,14f,8f,18f,16f,18f,19f,14f,19f,11f); line(12f,18f,12f,22f) }
            "fork" -> { line(7f,20f,7f,5f); path(7f,14f,17f,9f,17f,4f); drawCircle(color,2f*u,Offset(7f*u,3f*u),style=stroke); drawCircle(color,2f*u,Offset(17f*u,2f*u),style=stroke); drawCircle(color,2f*u,Offset(7f*u,22f*u),style=stroke) }
            "copy" -> { drawRoundRect(color,Offset(7f*u,7f*u),Size(13f*u,14f*u),style=stroke); path(15f,3f,3f,3f,3f,16f) }
            "share" -> { path(5f,10f,5f,21f,19f,21f,19f,10f); line(12f,15f,12f,2f); path(7f,7f,12f,2f,17f,7f) }
            "refresh" -> { drawArc(color,35f,285f,false,Offset(3f*u,3f*u),Size(18f*u,18f*u),style=stroke); path(20f,3f,20f,9f,14f,9f) }
            "person" -> { drawCircle(color,4f*u,Offset(12f*u,6f*u),style=stroke); path(3f,22f,4f,17f,8f,13f,16f,13f,20f,17f,21f,22f) }
            else -> for (x in listOf(5f,12f,19f)) drawCircle(color,1.5f*u,Offset(x*u,12f*u))
        }
    }
}
@Composable internal fun IconAction(icon: String, label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label; role = Role.Button }.clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        ProductIcon(icon, color = if (enabled) color else color.copy(alpha = .3f))
    }
}
