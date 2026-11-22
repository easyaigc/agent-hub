package com.agenthub.android

import org.json.JSONObject

/** Observation of one recorded run, independent from the next-send preference. */
data class EasyWorkTaskSpeed(val runId: String,val requested: String?,val actual: String?,val status: String) {
    fun json()=JSONObject().put("version",1).put("runId",runId).put("requested",requested).put("actual",actual).put("status",status)
    companion object {
        private fun speed(value: Any?)=(value as? String)?.takeIf { it in listOf("standard","fast","ultrafast") }
        fun decode(value: JSONObject?): EasyWorkTaskSpeed? {
            value?:return null
            val id=(value.opt("runId") as? String)?.takeIf { it.isNotEmpty() }?:return null
            if(value.opt("version")!=1 || value.opt("status") !in listOf("reported","unconfirmed"))return null
            val actual=if(value.opt("status")=="reported")speed(value.opt("actual"))else null
            return EasyWorkTaskSpeed(id,speed(value.opt("requested")),actual,if(actual==null)"unconfirmed" else "reported")
        }
        fun project(run: JSONObject?): EasyWorkTaskSpeed? {
            run?:return null
            val id=(run.opt("id") as? String)?.takeIf { it.isNotEmpty() }?:return null
            val requested=speed(run.optJSONObject("settings")?.opt("speed"))
            val report=decode(run.optJSONObject("generationSpeed"))
            if(report!=null&&report.runId==id&&report.requested==requested)
                return if(run.opt("status")=="completed")report else EasyWorkTaskSpeed(id,requested,null,"unconfirmed")
            return requested?.let { EasyWorkTaskSpeed(id,it,null,"unconfirmed") }
        }
    }
}
