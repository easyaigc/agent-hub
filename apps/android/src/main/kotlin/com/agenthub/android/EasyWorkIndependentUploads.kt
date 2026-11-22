package com.agenthub.android

import org.json.JSONObject

/** Authenticated adapter metadata only. Chat attachment budgets remain unchanged. */
internal data class EasyWorkFileLimits(val maximum:Long?,val chunk:Int,val negotiated:Boolean) {
    fun validate(size:Long) {require(size>0&&(maximum==null||size<=maximum)) {"本次传输超过电脑支持的文件大小；原文件和原记录已保留。"}}
    fun catalogMaximum(v:JSONObject):Long? {
        require(v.has("maxFileBytes"))
        val n=if(v.isNull("maxFileBytes"))null else uploadLong(v.get("maxFileBytes")).also {require(it>0)}
        require(!negotiated||n==maximum)
        return if(negotiated)n else minOf(n?:LEGACY,LEGACY)
    }
    companion object {
        const val LEGACY=20L*1024*1024
        fun decode(context:JSONObject):EasyWorkFileLimits {
            if("resources:transfer-limits:v1" !in context.getJSONArray("capabilities").strings())return EasyWorkFileLimits(LEGACY,EasyWorkFileTransfers.CHUNK,false)
            val v=context.getJSONObject("resourceTransferLimits");require(v.get("version")==1)
            fun maximum(key:String):Long? {require(v.has(key));return if(v.isNull(key))null else uploadLong(v.get(key)).also {require(it>0)}}
            maximum("uploadMaxBytes");maximum("downloadMaxBytes")
            val maximum=maximum("transferMaxBytes");val chunk=uploadLong(v.get("maxChunkBytes"));require(chunk>0)
            return EasyWorkFileLimits(maximum,minOf(chunk,EasyWorkFileTransfers.CHUNK.toLong()).toInt(),true)
        }
    }
}

/** Necessary wire projection; original-ID control and persistence stay in NativeUploadCenter. */
internal object EasyWorkIndependentUploads {
    fun receipt(t:JSONObject,host:String,device:String,directory:String):ComputerFileReceipt {
        require(t.getString("purpose")=="transfer"&&t.getString("direction")=="mobile-to-desktop"&&t.isNull("threadId")&&
            t.getString("mobileDeviceId")==device&&device.isNotBlank())
        val file=ComputerFileManifest(host,t.getString("transferId"),t.getString("name"),t.getString("contentType"),uploadLong(t.get("size")),t.getString("expectedSha256"))
        ComputerFileCodec.manifest(file.json())
        val phase=t.getString("status");require(phase in setOf("waiting","transferring","verifying","completed"))
        val count=uploadLong(t.get("transferredBytes"));require(count<=file.sizeBytes)
        val completed=phase=="completed"
        if(completed)require(count==file.sizeBytes&&t.getString("sha256")==file.sha256)
        val path=if(completed)t.getString("absolutePath") else directory.trimEnd('/')+"/"+file.name
        return ComputerFileReceipt(file,if(completed)"completed" else "prepared",path,t.getString("createdAt"))
            .also {ComputerFileCodec.receipt(it.json(),host)}
    }
    suspend fun prepare(m:NativeUploadManifest,device:String,target:EasyWorkTarget,limits:EasyWorkFileLimits,
        request:suspend (String,JSONObject,String?)->JSONObject):JSONObject {
        val params=JSONObject().put("transferId",m.id).put("name",m.file.name).put("mediaType",m.file.mediaType)
            .put("size",m.file.sizeBytes).put("expectedSha256",m.file.sha256).put("purpose","transfer")
        try {return request("resource/upload-status",JSONObject().put("transferId",m.id),null).also {EasyWorkFileTransfers.uploadTransfer(it,m,device)}}
        catch(e:EasyWorkRejection) {if(e.code?.uppercase()?.replace('-','_') !in setOf("NOT_FOUND","TRANSFER_NOT_FOUND","RESOURCE_NOT_FOUND"))throw e}
        val id="file-prepare-${m.id}"
        val original=request("command/status",JSONObject().put("commandId",id),null)
        if(original.length()!=0) {
            val hash=easyHash(easyCanonical(JSONObject().put("target",target.json()).put("method","resource/upload").put("params",params)).toByteArray())
            require(original.getString("operationId")==id&&original.getString("method")=="resource/upload"&&
                original.getString("deviceId")==device&&original.getString("requestHash")==hash&&EasyWorkTarget.decode(original.getJSONObject("target"))==target)
            check(original.optString("state")=="completed") {"原文件准备结果待核对；不会重新上传。"}
            val response=original.getJSONObject("response")
            require(response.get("protocolVersion")==2&&response.getString("kind")=="response"&&response.getString("requestId")==id&&response.get("ok")==true)
            return response.getJSONObject("result").also {EasyWorkFileTransfers.uploadTransfer(it,m,device)}
        }
        limits.validate(m.file.sizeBytes)
        return request("resource/upload",params,id).also {EasyWorkFileTransfers.uploadTransfer(it,m,device)}
    }
}
