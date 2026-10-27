package com.agenthub.android

import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** An actionable resource always comes from the original message DTO. Names alone grant no access. */
@Composable internal fun MessageResourceCards(message: MobileMessage) {
    val context=LocalContext.current
    val colors=MaterialTheme.colorScheme
    val open=LocalMessageResourceOpen.current
    val loading=LocalOpeningMessageAnchor.current
    val names=message.attachments.map { it.name }.toSet()
    message.files.filter { it !in names }.distinct().forEach {
        Text(productText("文件 · %@",it),Modifier.padding(top=6.dp),fontSize=12.sp,color=colors.onSurfaceVariant)
    }
    message.attachments.forEach { file->key(file.id,file.sha256) {
        val anchor="resource-card:"+file.id
        val busy=loading==anchor
        val enabled=loading==null&&open!=null
        val reference=Uri.Builder().scheme("codex-attachment").authority("open").appendQueryParameter("id",file.id).build().toString()
        // Match inline approvals: reserve the floating return-to-latest lane.
        // A visible share action must keep its native touch target outside that overlay.
        Surface(Modifier.fillMaxWidth().padding(top=6.dp,end=LocalMessageActionEndInset.current).testTag("resource-card-"+file.id),
            shape=RoundedCornerShape(12.dp),color=colors.surface) {
            Column(Modifier.padding(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={ open?.invoke(reference,anchor,MessageResourceAction.Preview) },enabled=enabled,
                        modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("resource-card-preview-"+file.id),contentPadding=PaddingValues(0.dp)) {
                        ProductIcon(if(file.mediaType.startsWith("image/"))"photo" else "doc",Modifier.size(20.dp),color=colors.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(file.name,Modifier.fillMaxWidth().testTag("resource-card-name-"+file.id),fontSize=12.sp,
                                maxLines=1,overflow=TextOverflow.Ellipsis,color=colors.onSurface)
                            Text(Formatter.formatShortFileSize(context,file.sizeBytes),fontSize=11.sp,color=colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp));Text(productText("预览"),fontSize=12.sp)
                    }
                    val shareLabel=productText("保存或分享文件")
                    Box(Modifier.size(48.dp).testTag("resource-card-share-"+file.id).semantics {
                        contentDescription=shareLabel;role=Role.Button
                    }.clickable(enabled=enabled) { open?.invoke(reference,anchor,MessageResourceAction.Share) },
                        contentAlignment=Alignment.Center) {
                        if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        else ProductIcon("share",color=if(enabled)colors.onSurface else colors.onSurface.copy(alpha=.3f))
                    }
                }
                if(busy)Text(productText("正在准备文件…"),Modifier.testTag("resource-card-wait-"+file.id),fontSize=12.sp,color=colors.onSurfaceVariant)
            }
        }
    } }
}
