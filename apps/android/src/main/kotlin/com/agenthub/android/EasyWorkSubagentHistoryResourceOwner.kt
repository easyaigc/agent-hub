package com.agenthub.android

/** One owner resolver for downloads, immutable previews, retries and document links. */
internal object MessageResourceOwnerResolver {
    fun resolve(owner: MobileSession.MessageLinkContext,key: String,selection: Long,connection: Long,
        thread: String,parentMessages: List<MobileMessage>,immutable: Boolean): MobileSession.MessageLinkContext? {
        if(owner.key!=key || owner.selection!=selection || owner.connection!=connection)return null
        if(owner.subagentCurrent!=null) {
            // A child is never found or authorized through the parent's message array.
            if(owner.resourceThread==null || !owner.subagentCurrent.invoke())return null
            return owner
        }
        if(owner.resourceThread!=null && owner.resourceThread!=thread)return null
        val latest=parentMessages.singleOrNull { it.id==owner.message.id }?:return null
        if(immutable) {
            val original=owner.message
            if(latest.role!=original.role || latest.kind!=original.kind || latest.replyMessageId!=original.replyMessageId ||
                latest.attachments!=original.attachments || latest.files!=original.files)return null
        } else if(latest!=owner.message)return null
        return owner.copy(message=latest,resourceThread=owner.resourceThread?:thread)
    }
    fun captureChild(base: MobileSession.MessageLinkContext,model: NativeSubagentHistoryModel,
        page: MobileSubagentHistory?,currentOwner: ()->Boolean): MobileSession.MessageLinkContext {
        val generation=model.requestGeneration
        return base.copy(subagentCurrent={
            page!=null && !model.isClosed && !model.busy && model.requestGeneration==generation &&
                model.page===page && currentOwner() && base.resourceThread!=null &&
                model.messages.any { it==base.message } && base.message.attachments.all {
                    it.hostId==page.hostId && it.threadId==base.resourceThread
                }
        })
    }
    fun readingIdentity(owner: MobileSession.MessageLinkContext,file: MobileFile): String =
        // Original resource thread and message, never the currently selected parent or row index.
        PreviewReadingIdentity.message(owner.key+":"+file.threadId,owner.message.replyMessageId?:owner.message.id,PreviewReadingIdentity.attachment(file))
}
