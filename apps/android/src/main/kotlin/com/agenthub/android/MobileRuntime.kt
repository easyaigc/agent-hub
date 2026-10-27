package com.agenthub.android

import org.json.JSONObject

data class MobileQuestionOption(val label: String,val description: String)
data class MobileQuestion(val id: String,val question: String,val options: List<MobileQuestionOption>,
    val multiple: Boolean,val custom: Boolean,val secret: Boolean)
data class MobileApproval(val id: String,val threadId: String,val turnId: String,val kind: String,
    val title: String,val detail: String,val questions: List<MobileQuestion>,val decisions: List<String>,val interactionMode: String?,
    val notificationRequestId: String?=null,val sourceItemId: String?=null,val decisionLabels: Map<String,String> = emptyMap())
data class MobilePlanStep(val step: String,val status: String)
data class MobileProposedPlan(val id: String,val turnId: String,val text: String)
data class MobileRuntime(val state: String,val turnId: String?,val plan: List<MobilePlanStep>,
    val error: String?,val approvals: List<MobileApproval>,val receipts: List<DeliveryReceipt>,val canControl: Boolean?,
    val collaborationMode: String?,val proposedPlan: MobileProposedPlan?,val settings: GenerationSettings) {
    companion object {
        fun decode(v: JSONObject,hostId: String,threadId: String): MobileRuntime {
            CodexCommandCodec.validate(v,"runtime")
            val receipts=v.getJSONArray("receipts").objects().map(CodexCommandCodec::receipt)
            require(receipts.all { it.hostId==hostId && it.threadId==threadId }) { "运行时回执目标不匹配" }
            val approvals=v.getJSONArray("approvals").objects().map { a ->
                val questions=a.getJSONArray("questions").objects().map { q -> MobileQuestion(q.getString("id"),q.getString("question"),
                    q.optJSONArray("options")?.objects()?.map { MobileQuestionOption(it.getString("label"),it.getString("description")) }.orEmpty(),
                    q.booleanOrNull("multiple")==true,q.booleanOrNull("custom")!=false,q.booleanOrNull("isSecret")==true) }
                require(questions.map { it.id }.distinct().size==questions.size) { "问题 ID 重复" }
                val labels=a.optJSONObject("decisionLabels")?.let { values -> values.keys().asSequence().associateWith { values.getString(it) } }.orEmpty()
                MobileApproval(a.getString("id"),a.getString("threadId"),a.getString("turnId"),a.getString("kind"),a.getString("title"),a.getString("detail"),questions,a.getJSONArray("decisions").strings(),a.optional("interactionMode"),a.optional("notificationRequestId"),a.optional("sourceItemId"),labels)
            }
            require(approvals.all { it.threadId==threadId } && approvals.map { it.id }.distinct().size==approvals.size) { "审批目标不匹配" }
            val plan=v.optJSONObject("proposedPlan")?.let { MobileProposedPlan(it.getString("id"),it.getString("turnId"),it.getString("text")) }
            val settings=v.optJSONObject("generationSettings")
            return MobileRuntime(v.getString("state"),v.optional("turnId"),v.getJSONArray("plan").objects().map { MobilePlanStep(it.getString("step"),it.getString("status")) },
                v.optional("error"),approvals,receipts,v.booleanOrNull("canControl"),v.optional("collaborationMode"),plan,
                GenerationSettings(settings?.optional("model"),settings?.optional("effort"),settings?.optional("permissionMode"),settings?.optional("speed")))
        }
    }
}
internal fun JSONObject.booleanOrNull(name: String): Boolean? = if(!has(name) || isNull(name)) null else getBoolean(name)
