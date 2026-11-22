package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.commonmark.node.Node
import org.commonmark.node.Image as MarkdownImage
import java.net.URI

internal enum class NativeMessageMediaKind(val icon: String) { Image("photo"),Video("image"),Audio("mic") }
internal object NativeMessageMedia {
    // Classify only; authorization remains with MessageLinkOpening and the original resource DTO.
    fun kind(reference: String): NativeMessageMediaKind? {
        if(reference.any { it.code<32 })return null
        val url=runCatching { URI(reference) }.getOrNull()?:return null
        if(url.rawUserInfo!=null)return null
        if(MessageLinkDestination.resolve(reference).kind !in listOf("web","computerFile","attachment"))return null
        return when(url.path.orEmpty().substringAfterLast('.').lowercase()) {
            "mp4","m4v","mov","webm","mkv"->NativeMessageMediaKind.Video
            "mp3","m4a","wav","aac","flac","ogg"->NativeMessageMediaKind.Audio
            else->NativeMessageMediaKind.Image
        }
    }
    fun images(node: Node): List<MarkdownImage> = buildList {
        fun visit(value: Node) {
            if(value is MarkdownImage) { add(value);return }
            NativeMarkdown.children(value).forEach(::visit)
        }
        visit(node)
    }
}

@Composable internal fun NativeMessageMediaCards(node: Node,tag: String) {
    // Document previews own their relative-link resolver; this is only the chat body.
    if(LocalMarkdownLinkHandler.current!=null)return
    val open=LocalMessageLinkOpen.current?:return
    val loading=LocalOpeningMessageAnchor.current
    val media=remember(node) { NativeMessageMedia.images(node).mapNotNull { image->NativeMessageMedia.kind(image.destination)?.let { image to it } } }
    if(media.isEmpty())return
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        media.forEachIndexed { index,(image,kind)->
            val anchor="$tag-media-$index"
            val title=remember(image) { NativeMarkdown.inline(image,androidx.compose.ui.graphics.Color.Unspecified,imageLabel="",linkedImageLabel="").text }
            OutlinedCard(onClick={ open(image.destination,anchor) },enabled=loading==null,
                modifier=Modifier.fillMaxWidth().testTag("$anchor-${kind.name.lowercase()}")) {
                LocalNativeMessageMediaSnapshots.current?.images?.get(image.destination)?.takeIf { kind==NativeMessageMediaKind.Image }?.let { thumbnail ->
                    Image(thumbnail.asImageBitmap(),title,Modifier.fillMaxWidth().heightIn(max=220.dp).testTag("$anchor-thumbnail"),contentScale=ContentScale.Fit)
                }
                Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(kind==NativeMessageMediaKind.Video)Text("▶") else ProductIcon(kind.icon)
                    Text(title.ifEmpty { image.destination.substringBefore('?').substringAfterLast('/').ifEmpty { productText("预览") } },Modifier.weight(1f))
                    if(loading==anchor)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                }
            }
        }
    }
}
