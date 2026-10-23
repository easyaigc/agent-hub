package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** EWDAP response mapping. Captured routing is authoritative; unknown query outcomes never borrow a current run. */
internal object EasyWorkReceiptNormalization {
    fun decode(value: JSONObject,operationId: String,hostId: String,threadId: String,defaultState: String,
        fallbackTurnId: String?=null,fallbackError: String?=null): DeliveryReceipt {
        fun text(name: String): String? {
            val raw=value.opt(name)
            if(raw==null||raw==JSONObject.NULL)return null
            require(raw is String) { "\u7535\u8111\u56de\u6267\u683c\u5f0f\u65e0\u6548" };return raw
        }
        fun flag(name: String): Boolean? {
            val raw=value.opt(name)
            if(raw==null||raw==JSONObject.NULL)return null
            require(raw is Boolean) { "\u7535\u8111\u56de\u6267\u683c\u5f0f\u65e0\u6548" };return raw
        }
        val rawFiles=value.opt("attachmentIds")
        val files=if(rawFiles==null||rawFiles==JSONObject.NULL)emptyList() else {
            require(rawFiles is JSONArray) { "\u7535\u8111\u56de\u6267\u683c\u5f0f\u65e0\u6548" }
            (0 until rawFiles.length()).map { index -> rawFiles.get(index).also { require(it is String) { "\u7535\u8111\u56de\u6267\u683c\u5f0f\u65e0\u6548" } } as String }
        }
        return DeliveryReceipt(hostId,threadId,operationId,value.opt("state") as? String ?: defaultState,
            value.opt("runId") as? String ?: value.opt("turnId") as? String ?: fallbackTurnId,
            if(value.has("error"))text("error") else fallbackError,text("supersededByTurnId"),text("text"),files,
            text("noticeContext"),text("queueSource"),flag("queuePresent"),flag("canExecuteNow"),text("queueDisposition"),text("queueSyncError"),text("laterTurnId"))
    }
}
