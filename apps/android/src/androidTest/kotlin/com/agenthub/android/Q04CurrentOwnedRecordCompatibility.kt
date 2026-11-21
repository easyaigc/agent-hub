package com.agenthub.android

import android.content.Context
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** PRIVATE CANDIDATE ONLY. Existing-key, existing-namespace, read-only leaf.
 * Called only under a NEW Root-frozen read-only outer method/elapsed lease and the original
 * existing writer.lock. No Activity, Session, ordinary writer, network or successor authority.
 * Decrypted text is local to this invocation; status contains booleans/counts/digests only.
 */
internal object Q04CurrentOwnedRecordCompatibility {
    private const val MAX_TEXT=1_048_576
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString(""){"%02x".format(it)}
    private fun sha(text: String): String {
        val bytes=text.toByteArray(Charsets.UTF_8)
        return try {sha(bytes)} finally {bytes.fill(0)}
    }
    private fun strictValue(text: String): Any {
        val bytes=text.toByteArray(Charsets.UTF_8)
        return try {StrictMigrationJson.value(bytes)} finally {bytes.fill(0)}
    }
    private fun strictObject(text: String)=strictValue(text) as JSONObject
    private fun filename(name: String)=sha(name)+".bin"
    private fun relative(name: String)="no-backup/q02-session/"+filename(name)
    private fun arrayHash(values: Collection<String>)=sha(JSONArray(values.sorted()).toString())

    fun inspect(context: Context,owned: Q04ExistingOwnedProfile,target: TargetScope,thread: String,
        inventory: Map<String,Q04CurrentOwnedCacheGraph.CipherEntry>,currentUnresolvedFilename: String,
        removedHistoricalFilename: String,expectedOriginalOperationId: String,expectedOriginalTurnId: String,checkLease: ()->Unit,unchanged: ()->Unit): JSONObject {
        require(inventory.size==11&&inventory.values.sumOf {it.bytes}==398796L)
        require(inventory.keys.all {it.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin"))})
        require(inventory.values.all {it.bytes in 29..MAX_TEXT.toLong()&&it.sha256.matches(Regex("[a-f0-9]{64}"))})
        require(currentUnresolvedFilename.matches(Regex("[a-f0-9]{64}\\.bin")))
        require(removedHistoricalFilename.matches(Regex("[a-f0-9]{64}\\.bin")))
        require(target.agentId=="codex"&&sameScope(target,owned.client.scope))
        require(UUID.fromString(thread).toString()==thread)
        val key=owned.client.cacheKey(thread)
        val historyName="history:$key"
        // The historical admission document's six names remain immutable. This read has no permit.
        require(owned.mutableRecords==setOf("metadata","operations:$key",historyName,"$historyName:parts",
            "ai-sharing-v1","submission:q04-remaining"))
        val fixed=Q02RealReadInstallation.FilesContext(context,owned.root)
        val authenticated=linkedSetOf<String>()
        val semantic=linkedMapOf<String,String>()
        val semanticFailures=JSONArray()
        val rows=linkedMapOf<String,JSONObject>()
        var primary: Throwable?=null
        try {
            NativeSessionStorage.ExistingReader.open(fixed,"q02-session",owned.alias).use {reader->
                fun read(name: String): String {
                    checkLease();unchanged()
                    val entry=inventory.getValue(relative(name)) // No hashed-filename probing or aliases.
                    val raw=reader.text(name,MAX_TEXT)
                    unchanged();checkLease()
                    authenticated.add(name)
                    rows[name]=JSONObject().put("logicalNameSha256",sha(name)).put("cipherRelativePath",relative(name))
                        .put("cipherSha256",entry.sha256).put("cipherBytes",entry.bytes)
                        .put("logicalNameAuthenticated",true)
                    return raw
                }
                val metadataText=read("metadata")
                val metadata=JSONObject(metadataText)
                require(metadata.get("version")==1)
                val client=metadata.getJSONArray("clients").objects().map(::decodeClient).single()
                require(!client.isDemo&&client.id==owned.client.id&&sameScope(client.scope,target)&&client.cacheKey(thread)==key)
                for(name in Q04ExistingOwnedProfile.protectedNames) {
                    require(read(name)==owned.protectedRecords.getValue(name))
                    semantic[name]="actual-owned-inspect-production-identity-vault-binding"
                }
                val journalText=read("operations:$key")
                val operationRows=NativeOperationJournal.validateExistingMigrationRecords(key,JSONObject(journalText))
                val original=operationRows.single()
                require(original.id==expectedOriginalOperationId&&original.id==owned.original.id&&original.kind=="reply"&&original.phase=="completed"&&original.submissionAttempted)
                require(original.intent==owned.original.intent&&original.intentSha256==owned.original.intentSha256)
                val originalReceipt=CodexCommandCodec.receipt(JSONObject(checkNotNull(original.result)))
                require(originalReceipt.operationId==original.id&&originalReceipt.hostId==owned.client.id&&
                    originalReceipt.threadId==thread&&originalReceipt.turnId==expectedOriginalTurnId)
                val originalIntentBytes=original.intent.toByteArray(Charsets.UTF_8)
                val admittedIntentBytes=owned.original.intent.toByteArray(Charsets.UTF_8)
                val originalIntentSize=originalIntentBytes.size
                try {require(originalIntentBytes.contentEquals(admittedIntentBytes))}
                finally {originalIntentBytes.fill(0);admittedIntentBytes.fill(0)}
                semantic["operations:$key"]="actual-NativeOperationJournal-and-deliveryReceipt-original-completed"
                val submissionName="submission:${original.id}"
                val submissionText=read(submissionName)
                val sharingText=read("ai-sharing-v1")

                // Exact production pre-POST template, CodexPrivateConnection.authorize. All inputs
                // come from the already authenticated identity/current client; exactly ONE name.
                // This is a candidate until its physical filename and real GCM authentication match.
                val identity=JSONObject(owned.protectedRecords.getValue("easywork-device-identity"))
                val actualTarget=checkNotNull(owned.client.scope)
                val claim=JSONObject().put("version",1).put("target",encodeTarget(actualTarget))
                    .put("publicKey",identity.getString("publicKey"))
                val claimIntent=JSONObject().put("claim",claim)
                    .put("endpoint",CodexEndpoints.local(checkNotNull(owned.client.endpoint)))
                    .put("fingerprint",checkNotNull(owned.client.codexDirect).fingerprint).toString()
                val claimName=NativeMigrationPrivateClaims.ACTIVE_PREFIX+sha(claimIntent)
                require(filename(claimName)==currentUnresolvedFilename) // Mismatch leaves UNKNOWN open.
                val actualClaim=read(claimName)
                require(actualClaim==claimIntent)
                NativeMigrationPrivateClaims.validateOriginal(mapOf(claimName to actualClaim),emptyMap())
                semantic[claimName]="actual-NativeMigrationPrivateClaims.validateOriginal"

                val indexText=read(historyName)
                val index=JSONObject(indexText)
                require(index.get("version")==2&&index.getString("targetKey")==key)
                val generation=index.getString("generation")
                require(UUID.fromString(generation).toString()==generation)
                val parts=index.getJSONArray("parts").strings()
                require(parts.size<=inventory.size&&parts.distinct().size==parts.size)
                parts.forEachIndexed {position,name->require(name=="$historyName:part:$generation:$position")}
                val cacheTexts=linkedMapOf(historyName to indexText)
                val history=checkNotNull(NativeHistoryCache.decode(key) {name->
                    require(name==historyName||name in parts)
                    if(name==historyName)indexText else read(name).also {cacheTexts[name]=it}
                })
                require(history.hostId==owned.client.id&&history.threadId==thread&&sameScope(history.scope,target))
                require(history.messages.size==index.getInt("messageCount"))

                val ledgerText=read(NativeReadingLedger.STORAGE_NAME)
                val ledgerPayloadBytes=ledgerText.toByteArray(Charsets.UTF_8).let {try {it.size} finally {it.fill(0)}}
                val ledgerPayloadHash=sha(ledgerText) // Raw UTF-8 bytes, before parse/canonicalization.
                require(authenticated.map(::relative).toSet()==inventory.keys&&authenticated.size==11)
                require(inventory.keys.none {it.substringAfterLast('/')==removedHistoricalFilename})
                fun semanticAttempt(stage: String,block: ()->Unit): Boolean=try {block();true} catch(failure: Throwable) {
                    semanticFailures.put(JSONObject().put("stage",stage).put("exceptionClass",failure.javaClass.name));false
                }
                val ancillaryOk=semanticAttempt("actual-ancillary-consent-validator") {
                    require(NativeMigrationAncillary.validate(mapOf("ai-sharing-v1" to strictObject(sharingText)))==setOf("ai-sharing-v1"))
                    semantic["ai-sharing-v1"]="actual-NativeMigrationAncillary.validate"
                }
                val historyGraphOk=semanticAttempt("actual-history-closed-graph-validator") {
                    val values=cacheTexts.mapValues {strictValue(it.value)}
                    require(NativeMigrationHistory.validate(values,listOf(client))==cacheTexts.keys)
                    cacheTexts.keys.forEach {semantic[it]="actual-NativeHistoryCache.decode-and-NativeMigrationHistory.validate"}
                }
                val submissionGraphOk=semanticAttempt("actual-submission-reference-closure-validator") {
                    // The complete, unedited current metadata is included. References to any absent
                    // draft/payload remain a real production rejection; no empty projection is made.
                    // Ledger/active claim are separately validated above/below, not portable imports.
                    val subgraph=mapOf("metadata" to metadataText,"operations:$key" to journalText,submissionName to submissionText)
                    NativeMigrationArchiveValidation.stream(NativeMigrationStreamRecords(subgraph,emptyMap(),4))
                    semantic["metadata"]="actual-NativeMigrationArchiveValidation.metadata-dependency-closure"
                    semantic[submissionName]="actual-NativeMigrationArchiveValidation-submission-and-References-canonicalReplyIntent"
                }
                var parseOk=false;var constructorOk=false;var deltaOk=false;var pendingOk=false
                var roundTripOk=false;var roundTripForeignRowsStable=false;var roundTripHash: String?=null
                var failureStage: String?=null;var failureClass: String?=null
                var scopeCount: Int?=null;var foreignScopeCount: Int?=null;var rowCount: Int?=null
                var qaRows: Int?=null;var foreignRows: Int?=null;var pendingTotal: Int?=null
                var qaPending: Int?=null;var foreignPending: Int?=null;var inactivePending: Int?=null
                var foreignScopeHash: String?=null;var foreignRowHash: String?=null
                try {
                    failureStage="production-ledger-parse"
                    NativeReadingLedger.validateEncoded(ledgerText);parseOk=true
                    semantic[NativeReadingLedger.STORAGE_NAME]="actual-NativeReadingLedger.validateEncoded"
                    val ledger=JSONObject(ledgerText)
                    val scopes=ledger.getJSONArray("scopes").strings().toSet()
                    val entries=ledger.getJSONArray("rows").objects()
                    val qaScope=ThreadReadingIdentity.scopeKey(target)
                    fun qa(row: JSONObject)=sameScope(HostReadCodec.target(row.getJSONObject("target")),target)&&row.getString("threadId")==thread
                    scopeCount=scopes.size;foreignScopeCount=scopes.count {it!=qaScope}
                    val qaCount=entries.count(::qa);rowCount=entries.size;qaRows=qaCount;foreignRows=entries.size-qaCount
                    inactivePending=entries.count {it.has("pendingReader")&&ThreadReadingIdentity.scopeKey(checkNotNull(HostReadCodec.target(it.getJSONObject("target")))) !in scopes}
                    foreignScopeHash=arrayHash(scopes.filter {it!=qaScope})
                    foreignRowHash=sha(easyCanonical(JSONArray(entries.filterNot(::qa))))
                    failureStage="actual-production-save-codec-roundtrip"
                    // Root must supply the behavior-preserving extract of the ACTUAL save encoder.
                    // Returned text remains in this stack only and is never put into a report.
                    val savedProjection=NativeReadingLedger.canonicalEncodedForInspection(ledgerText)
                    roundTripHash=sha(savedProjection);roundTripOk=true
                    val savedRows=JSONObject(savedProjection).getJSONArray("rows").objects()
                    roundTripForeignRowsStable=sha(easyCanonical(JSONArray(savedRows.filterNot(::qa))))==foreignRowHash
                    failureStage="existing-owned-ledger-constructor"
                    val guard=NativeExistingOwnedReadingScope(target,thread,ledgerText);constructorOk=true
                    failureStage="actual-production-pending-readonly"
                    val store=NativeSessionStorage(fixed,"q02-session",owned.alias,
                        existingKeyOnly=true,existingNamespaceOnly=true,existingReadOnly=true)
                    val pending=try {NativeReadingLedger(store).pendingReads()} finally {store.retireWriter()}
                    unchanged();checkLease()
                    val allowedPending=pending.count {guard.allows(it.target,it.threadId)}
                    pendingTotal=pending.size;qaPending=allowedPending;foreignPending=pending.size-allowedPending;pendingOk=true
                    // Only a memory copy is changed. Never call a store write or a Session consumer.
                    failureStage="memory-only-qa-delta-validator"
                    val proposed=JSONObject(savedProjection)
                    val proposedRows=proposed.getJSONArray("rows")
                    val qaExisting=proposedRows.objects().singleOrNull(::qa)
                    if(qaExisting==null)proposedRows.put(JSONObject().put("target",CodexCommandCodec.target(target))
                        .put("threadId",thread).put("replyUnread",true).put("notices",JSONObject()))
                    else qaExisting.put("replyUnread",!qaExisting.getBoolean("replyUnread"))
                    proposed.put("revision",Math.addExact(readingInteger(ledger,"revision"),1L))
                        .put("scopes",JSONArray((scopes+qaScope).toList()))
                    val actualSaveProjection=NativeReadingLedger.canonicalEncodedForInspection(proposed.toString())
                    guard.validate(ledgerText,actualSaveProjection);deltaOk=true
                    failureStage=null
                }catch(failure: Throwable) {failureClass=failure.javaClass.name} // No message/stack/payload.
                unchanged();checkLease()
                rows.forEach {(name,row)->row.put("semanticValidated",name in semantic)
                    .put("semanticValidator",semantic[name]?:JSONObject.NULL)}
                require(semantic.keys.all {it in authenticated})
                val allSemantic=semantic.size==11&&semantic.keys==authenticated
                return JSONObject().put("kind","q04-current-records-ledger-readonly-compatibility-v1")
                    .put("currentInventoryFiles",11).put("currentInventoryBytes",398796)
                    .put("authenticatedLogicalNameCount",authenticated.size).put("authenticatedRecordCount",authenticated.size)
                    .put("semanticValidatedRecordCount",semantic.size).put("all11Authenticated",true)
                    .put("all11SemanticallyValidated",allSemantic).put("productionDecodedRecords",semantic.size)
                    .put("ancillaryConsentProductionValidationPassed",ancillaryOk)
                    .put("historyProductionGraphValidationPassed",historyGraphOk)
                    .put("submissionProductionReferenceClosurePassed",submissionGraphOk)
                    .put("semanticDiagnosticFailures",semanticFailures).put("currentUnknownCount",0)
                    .put("originalOperationId",original.id).put("originalTurnId",originalReceipt.turnId)
                    .put("originalOperationCompleted",true).put("originalCompletedIntentReceiptMatched",true)
                    .put("originalIntentUtf8Bytes",originalIntentSize).put("originalIntentUtf8Sha256",original.intentSha256)
                    .put("originalIntentBytesUnchanged",true)
                    .put("currentClaimDerivedFromProductionTemplate",true).put("currentClaimAuthenticatedAndValidated",true)
                    .put("records",JSONArray(rows.values.toList()))
                    .put("ledgerPayloadUtf8Sha256",ledgerPayloadHash).put("ledgerPayloadUtf8Bytes",ledgerPayloadBytes)
                    .put("ledgerCipherRelativePath",relative(NativeReadingLedger.STORAGE_NAME))
                    .put("ledgerCipherSha256",inventory.getValue(relative(NativeReadingLedger.STORAGE_NAME)).sha256)
                    .put("ledgerCipherBytes",inventory.getValue(relative(NativeReadingLedger.STORAGE_NAME)).bytes)
                    .put("productionLedgerParseCompatible",parseOk).put("ownedLedgerConstructorCompatible",constructorOk)
                    .put("memoryOnlyQaDeltaAccepted",deltaOk).put("productionPendingReadProjectionPassed",pendingOk)
                    .put("actualProductionSaveRoundtripPassed",roundTripOk)
                    .put("roundtripForeignCanonicalRowsPreserved",roundTripForeignRowsStable)
                    .put("productionRoundtripUtf8Sha256",roundTripHash?:JSONObject.NULL)
                    .put("onlyExactQaRowMutableUnderCurrentGuard",deltaOk)
                    .put("scopeCount",scopeCount?:JSONObject.NULL).put("foreignScopeCount",foreignScopeCount?:JSONObject.NULL)
                    .put("rowCount",rowCount?:JSONObject.NULL).put("qaRowCount",qaRows?:JSONObject.NULL)
                    .put("foreignRowCount",foreignRows?:JSONObject.NULL).put("pendingTotal",pendingTotal?:JSONObject.NULL)
                    .put("pendingQaAllowed",qaPending?:JSONObject.NULL).put("pendingForeignExcluded",foreignPending?:JSONObject.NULL)
                    .put("pendingOutsideActiveScopes",inactivePending?:JSONObject.NULL)
                    .put("foreignScopeSetSha256",foreignScopeHash?:JSONObject.NULL)
                    .put("foreignCanonicalRowsSha256",foreignRowHash?:JSONObject.NULL)
                    .put("diagnosticFailureStage",failureStage?:JSONObject.NULL).put("diagnosticFailureClass",failureClass?:JSONObject.NULL)
                    .put("historicalMutableNamesUnchanged",true).put("historicalMutableNameCount",6)
                    .put("historicalDeletedNameProvenanceClosed",false).put("historicalDeletedLogicalName",JSONObject.NULL)
                    .put("compatibleForFutureLedgerPermit",allSemantic&&ancillaryOk&&historyGraphOk&&submissionGraphOk&&parseOk&&roundTripOk&&roundTripForeignRowsStable&&constructorOk&&pendingOk&&deltaOk)
                    .put("actualLedgerWrites",0).put("networkRequests",0).put("modelInputs",0)
                    .put("payloadsExported",false).put("credentialsExported",false)
            }
        }catch(failure: Throwable) {primary=failure;throw failure}
        finally {try {unchanged();checkLease()}catch(fence: Throwable){if(primary==null)throw fence else primary.addSuppressed(fence)}}
    }
}
