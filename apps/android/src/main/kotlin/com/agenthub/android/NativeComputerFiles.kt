package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Existing Host file-transfer wire only. Independent of chats, tasks and project CRUD. */
data class ComputerFileManifest(val hostId: String,val id: String,val name: String,
    val mediaType: String,val sizeBytes: Long,val sha256: String) {
    fun json()=JSONObject().put("hostId",hostId).put("id",id).put("name",name).put("mediaType",mediaType)
        .put("sizeBytes",sizeBytes).put("sha256",sha256)
}
data class ComputerFileReceipt(val file: ComputerFileManifest,val phase: String,
    val destinationPath: String,val createdAt: String) {
    fun validate(expected: ComputerFileManifest) {require(file==expected&&phase in setOf("prepared","completed")) {"电脑返回的文件回执不匹配。"}}
    fun json()=file.json().put("phase",phase).put("destinationPath",destinationPath).put("createdAt",createdAt)
}
data class ComputerFileCatalog(val hostId: String,val destinationDirectory: String,
    val maxFileBytes: Long?,val files: List<ComputerFileReceipt>)
class ComputerFileUpload(val file: ComputerFileManifest,private val reader: (OutputStream,()->Boolean,(Long)->Unit)->Unit) {
    fun copyTo(output: OutputStream,active: ()->Boolean,progress: (Long)->Unit) = reader(output,active,progress)
}
internal object ComputerFileCodec {
    const val MAX_FILE_BYTES=1_073_741_824L // F59-only Long/chunked storage; ordinary attachments unchanged.
    const val MAX_CATALOG=4096
    private val manifestKeys=setOf("hostId","id","name","mediaType","sizeBytes","sha256")
    fun uuid(value: String) {require(UUID.fromString(value).toString()==value)}
    fun hash(raw: ByteArray)=MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {"%02x".format(it)}
    private fun utf8Bytes(value: String)=Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)).remaining()
    fun integer(value: Any): Long {require(value is Int||value is Long);return (value as Number).toLong()}
    fun text(value: Any,max: Int): String=(value as String).also {require(it.isNotEmpty()&&utf8Bytes(it)<=max&&it.none {c->c=='\u0000'})}
    fun exact(value: JSONObject,keys: Set<String>) {require(value.keys().asSequence().toSet()==keys)}
    fun validMediaType(value: String)=value.length in 3..256&&Regex("[A-Za-z0-9_.+\\-]+/[A-Za-z0-9_.+\\-]+").matches(value)
    fun validName(value: String) {
        require(value.isNotBlank()&&utf8Bytes(value)<=180&&value !in setOf(".","..")&&
            value.none {it<' '||it=='\u007f'||it in "/\\:<>\"|?*"}&&!value.endsWith('.')&&!value.endsWith(' ')&&
            !Regex("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\.|$)").containsMatchIn(value)) {"请选择名称不含路径或系统保留字符的非空普通文件。"}
    }
    fun manifest(value: JSONObject): ComputerFileManifest {
        exact(value,manifestKeys)
        val file=ComputerFileManifest(text(value.get("hostId"),256),text(value.get("id"),36),text(value.get("name"),180),
            text(value.get("mediaType"),256),integer(value.get("sizeBytes")),text(value.get("sha256"),64))
        require(file.hostId.isNotBlank());uuid(file.id);validName(file.name)
        require(validMediaType(file.mediaType)&&
            file.sizeBytes in 1..9_007_199_254_740_991L&&Regex("[a-f0-9]{64}").matches(file.sha256))
        return file
    }
    fun receipt(value: JSONObject,hostId: String): ComputerFileReceipt {
        exact(value,manifestKeys+setOf("phase","destinationPath","createdAt"))
        val file=manifest(JSONObject().also {v->manifestKeys.forEach {v.put(it,value.get(it))}})
        require(file.hostId==hostId)
        val result=ComputerFileReceipt(file,text(value.get("phase"),16),text(value.get("destinationPath"),16384),text(value.get("createdAt"),128))
        require(result.phase in setOf("prepared","completed"));Instant.parse(result.createdAt)
        return result
    }
    fun catalog(value: JSONObject,hostId: String): ComputerFileCatalog {
        val keys=value.keys().asSequence().toSet()
        require(keys.containsAll(setOf("hostId","destinationDirectory","files"))&&keys.all {it in setOf("hostId","destinationDirectory","maxFileBytes","files")})
        require(value.get("hostId")==hostId)
        val maximum=if(!value.has("maxFileBytes")||value.get("maxFileBytes")==JSONObject.NULL)null else integer(value.get("maxFileBytes")).also {require(it in 1..9_007_199_254_740_991L)}
        val files=value.getJSONArray("files");require(files.length()<=MAX_CATALOG)
        val receipts=files.objects().map {receipt(it,hostId)};require(receipts.map {it.file.id}.distinct().size==receipts.size)
        return ComputerFileCatalog(hostId,text(value.get("destinationDirectory"),16384),maximum,receipts)
    }
}
internal data class ComputerFileTarget(val scope: TargetScope,val certificate: String?) {
    val key=ComputerFileCodec.hash(("computer-files-v1\u0000"+RuntimeIdentity.readingKey(scope,null)).toByteArray(Charsets.UTF_8))
    fun matches(client: MobileClient)=!client.isDemo&&client.id==scope.hostId&&RuntimeIdentity.matches(scope,client.scope)&&
        client.codexDirect?.fingerprint==certificate
    fun json()=JSONObject().put("scope",CodexCommandCodec.target(scope)).put("certificate",certificate?:JSONObject.NULL)
    companion object {
        fun capture(client: MobileClient): ComputerFileTarget {
            val scope=requireNotNull(client.scope);require(scope.hostId==client.id&&!client.isDemo)
            require(client.agentConnection==null||RuntimeIdentity.matches(scope,client.agentConnection.target))
            CodexCommandCodec.validate(CodexCommandCodec.target(scope),"target")
            val certificate=client.codexDirect?.fingerprint?.also {require(it.isNotEmpty()&&it.length<=8192)}
            return ComputerFileTarget(scope,certificate)
        }
    }
}

/** Only app-owned error keys enter localization; Host detail remains literal display data. */
internal object ComputerFileCopy {
    const val UNKNOWN="文件传输未确认，请查询原结果。"
    const val RESOURCES_PENDING="文件读取、保存或资源关闭尚未确认；请保留手机上的剩余内容，不会继续上传。"
    const val RECEIPT_SAVE_PENDING="电脑已确认接收；手机回执保存状态待核对。请查询原结果，核对前不会清理剩余原件。"
    const val CLEANUP_PENDING="电脑已确认接收；手机清理尚未确认，可能仅剩部分原件。保留原回执和清理入口，不会重新上传。"
    const val CONFIRMATION_CONFLICT="电脑返回的状态与已确认接收记录不一致；已知接收结果不变，请继续核对剩余内容和原回执。"
    const val REFRESH_FAILED="暂时无法读取电脑传输记录；已确认的接收结果不变。"
    const val LOCAL_SAVE_PENDING="手机文件保存状态待核对；剩余文件和临时记录不会自动清理，不会继续上传。"
    const val LOCAL_BUSY="文件存储正由另一个本机进程使用，请稍后核对。"
    const val ORIGINAL_INVALID="手机保存的原件校验失败，请保留原记录。"
    private val legacyCopy=mapOf(
        "文件读取或保存尚未完整结束，原件和记录保留；不会继续上传。" to "文件读取、保存或资源关闭尚未确认；请保留手机上的剩余内容，不会继续上传。",
        "电脑已确认接收；手机回执保存状态待核对，原件仍保留。请查询原结果核对。" to "电脑已确认接收；手机回执保存状态待核对。请查询原结果，核对前不会清理剩余原件。",
        "电脑已确认接收；手机本地清理待处理，未清理的原件和记录仍保留。" to "电脑已确认接收；手机清理尚未确认，可能仅剩部分原件。保留原回执和清理入口，不会重新上传。",
        "电脑返回的状态与已确认接收记录不一致；原件和记录已保留，请继续核对。" to "电脑返回的状态与已确认接收记录不一致；已知接收结果不变，请继续核对剩余内容和原回执。",
        "本地等待已结束，原件和传输记录保留。" to "本地等待已结束；远端操作未取消。已清理的原件不会恢复，剩余内容和原回执需继续核对。",
        "电脑尚无原传输回执，记录与原件已保留；不会重新上传。" to "电脑尚无原传输回执；请保留手机上的剩余内容并继续查询，不会重新上传。"
    )
    fun display(key: String)=legacyCopy[key]?:key
    fun receiptSavePending(key: String?)=key==RECEIPT_SAVE_PENDING||key=="电脑已确认接收；手机回执保存状态待核对，原件仍保留。请查询原结果核对。"
    val errors=legacyCopy.keys+setOf(LOCAL_SAVE_PENDING,LOCAL_BUSY,UNKNOWN,RESOURCES_PENDING,RECEIPT_SAVE_PENDING,CLEANUP_PENDING,CONFIRMATION_CONFLICT,REFRESH_FAILED,"此电脑尚未支持文件互传，请更新电脑连接程序。","此连接尚未支持文件互传，请更新电脑连接程序。",
        "一次最多选择 32 个文件。","保存的文件不属于当前电脑目标。","原件读取未完成，已保存的内容保留在手机。",
        "手机保存的原件校验失败，请保留原记录。","手机保存的待传文件已达到额度，请先核对已有传输。",
        "手机存储空间不足，未开始传输。","本地等待已结束；远端操作未取消。已清理的原件不会恢复，剩余内容和原回执需继续核对。",
        "电脑仍在等待或处理原传输；请继续查询，不会重新上传。","电脑尚无原传输回执；请保留手机上的剩余内容并继续查询，不会重新上传。",
        "电脑目标已变化，请关闭后重新打开文件互传。","电脑返回的文件回执不匹配。",
        "请选择名称不含路径或系统保留字符的非空普通文件。","请选择额度以内、大小明确且非空的普通文件。",
        "文件在读取时发生变化，请重新选择。")
    fun message(failure: Throwable): String {
        if(ComputerFileFdOwners.hasUnresolved()||failure is ComputerFileResourceCloseUnknown||failure.suppressed.any {it is ComputerFileResourceCloseUnknown})return RESOURCES_PENDING
        if(failure is NativeVoiceRawGate.Busy||failure is java.nio.channels.OverlappingFileLockException)return LOCAL_BUSY
        return if(failure !is CodexCommandError&&failure.message in errors)display(failure.message!!) else UNKNOWN
    }
}
