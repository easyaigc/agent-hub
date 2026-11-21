package com.agenthub.android

import java.security.MessageDigest
import java.util.UUID

/** Test-only, one task, one immutable attempt. This never provisions a storage key. */
internal object F73OwnedCleanupPolicy {
    const val TASK = "android-parallel-20261112-01a1cd5e"
    const val PROTECTED = "3c8688a7-0e7f-42dd-8b36-16974b36afd5"
    const val KNOWN_NEW_F73 = "3c621e8c-9d42-4a3e-b96e-adb55a7e89bc"
    const val KNOWN_SECOND_F73 = "531debf9-3184-4f87-bdd8-9d0301432b3c"
    const val KNOWN_THIRD_F73 = "2a67c359-fcdc-40a9-8728-3484db9e84ef"
    const val KNOWN_FOURTH_F73 = "e5e3be92-5706-4d88-bc51-5e34d04a5e06"
    const val API_ABSENT = "absent-at-public-AndroidKeyStore-API-observation"
    data class Scope(val agent: String, val host: String, val workspace: String, val runtime: String,
        val authority: String, val subject: String)
    data class Entry(val path: String, val directory: Boolean, val bytes: Long = 0,
        val sha256: String = "", val archivedSha256: String = "")
    data class Plan(val task: String, val operation: String, val run: String, val alias: String,
        val allowlist: Map<String,String>, val scope: Scope, val nativeScope: Scope,
        val mobileDeviceId: String, val nativeMobileDeviceId: String, val closed: Boolean,
        val originalExited: Boolean, val writerExited: Boolean, val instrumentationExited: Boolean,
        val ordinaryLan: Boolean, val revoked: Boolean, val revokeKnown: Boolean,
        val groupBindingIds: List<String>, val bindingId: String, val archiveVerified: Boolean,
        val inventory: List<Entry>, val manifestSha256: String, val allowedMode: String = "query")
    fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun hash(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
    fun rootAlias(run: String) = "q02-real-$run"
    // Exact production NativeSessionStorage.child formula, null generation only.
    fun childAlias(parent: String, suffix: String) = "agent-hub-child-" + sha((parent + "\u0000" + suffix).toByteArray()).take(48)
    // Exact production EasyWorkDPoP.load/loadExisting formula, public device ID only.
    fun dpopAlias(device: String) = "agent-hub-easywork-dpop-" + sha(device.toByteArray(Charsets.UTF_8))
    val childSuffixes = listOf("external-inbox", "recent-photos", "photo-permission", "document-results",
        "camera-results", "photo-results", "migration-candidates", "computer-file-transfers")
    fun guardedChildren(alias: String): Set<String> = childSuffixes.map { childAlias(alias,it) }.toSet() +
        listOf("document-results","camera-results","photo-results").flatMap { parent ->
            (listOf("payloads") + if(parent == "camera-results") listOf("outputs") else emptyList()).map {
                childAlias(childAlias(alias,parent),it)
            }
        }
    fun deletableAliases(plan: Plan) = listOf(plan.alias, dpopAlias(plan.mobileDeviceId))
    val fixedDirectories = setOf("", "no-backup", "no-backup/q02-session", "files", "cache", "cache/migration-ui-v1", "code-cache")
    fun validate(plan: Plan) {
        require(plan.task == TASK); uuid(plan.operation); uuid(plan.run); hash(plan.manifestSha256)
        require(plan.allowedMode in setOf("query","delete"))
        require(plan.run != PROTECTED)
        // Each lifecycle-tracked attempt authorizes only its exact run, never historical runs or a wider allowlist.
        // The identifier is planning only; Root's exact manifest and every proof gate remain required.
        if(plan.run in setOf(KNOWN_THIRD_F73,KNOWN_FOURTH_F73)) require(plan.allowlist == mapOf(plan.run to rootAlias(plan.run)))
        else {
            require(plan.allowlist.size in 1..2)
            require(plan.allowlist.keys.all {it in setOf(KNOWN_NEW_F73,KNOWN_SECOND_F73)}) { "Unknown run needs a reviewed allowlist update" }
        }
        for((run,alias) in plan.allowlist) { uuid(run); require(run != PROTECTED && alias == rootAlias(run)) }
        require(plan.allowlist[plan.run] == plan.alias && plan.alias == rootAlias(plan.run))
        require(plan.scope == plan.nativeScope && plan.scope.agent == "easywork")
        require(listOf(plan.scope.host,plan.scope.workspace,plan.scope.runtime,plan.scope.authority,plan.scope.subject).all { it.isNotEmpty() })
        require(plan.mobileDeviceId == plan.nativeMobileDeviceId && plan.mobileDeviceId.startsWith("mobile-"))
        uuid(plan.mobileDeviceId.removePrefix("mobile-"))
        require(plan.closed && plan.originalExited && plan.writerExited && plan.instrumentationExited)
        require(plan.ordinaryLan && plan.revoked && plan.revokeKnown)
        require(plan.groupBindingIds == listOf(plan.bindingId)); uuid(plan.bindingId)
        require(plan.archiveVerified)
        require(plan.inventory.map { it.path }.distinct().size == plan.inventory.size)
        require(plan.inventory.filter { it.directory }.map { it.path }.toSet() == fixedDirectories)
        val files = plan.inventory.filterNot { it.directory }
        require(files.size in 1..16 && files.sumOf { it.bytes } in 1..1_048_576)
        for(entry in files) {
            require(entry.path.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin")))
            require(entry.bytes in 29..1_048_576); hash(entry.sha256)
            require(entry.archivedSha256 == entry.sha256)
        }
        // Never admit a migration pointer or an unrelated logical root even if Root inventoried it.
        require(files.none { it.path.endsWith(sha("migration-active-generation-v1".toByteArray()) + ".bin") })
        require(deletableAliases(plan).distinct().size == 2)
    }
    interface Keys { fun contains(alias: String): Boolean; fun delete(alias: String) }
    interface Profile { fun remaining(plan: Plan, partial: Boolean): List<Entry>; fun remove(plan: Plan) }
    interface Journal { fun prior(): String?; fun claim(manifestSha256: String) }
    data class Observation(val intentPresent: Boolean, val remainingFiles: List<String>, val profileAbsent: Boolean,
        val aliases: Map<String,String>, val completeAtApiObservation: Boolean)
    fun query(plan: Plan, keys: Keys, profile: Profile, journal: Journal): Observation {
        validate(plan)
        val prior = journal.prior(); require(prior == null || prior == plan.manifestSha256)
        val remaining = profile.remaining(plan, prior != null)
        val aliases = (deletableAliases(plan) + guardedChildren(plan.alias).sorted()).associateWith {
            if(keys.contains(it)) "present-at-public-AndroidKeyStore-API-observation" else API_ABSENT
        }
        return Observation(prior != null,remaining.filterNot { it.directory }.map { it.path },remaining.isEmpty(),aliases,
            prior != null && remaining.isEmpty() && aliases.values.all { it == API_ABSENT })
    }
    fun delete(plan: Plan, keys: Keys, profile: Profile, journal: Journal): Observation {
        validate(plan)
        require(plan.allowedMode == "delete") { "Query-only Root authorization cannot be upgraded by a flag" }
        require(journal.prior() == null) { "Prior/unknown attempt: query only; deletion is never replayed" }
        require(profile.remaining(plan,false).toSet() == plan.inventory.toSet())
        require(guardedChildren(plan.alias).none(keys::contains)) { "Unexpected child key: preserve and investigate" }
        require(keys.contains(plan.alias)) { "No first delete when root key is already absent or unknown" }
        // DPoP is not expected in ordinary LAN. Preserve unexpected proof keys; this version deletes only root.
        require(!keys.contains(dpopAlias(plan.mobileDeviceId))) { "Unexpected DPoP key needs separate ownership review" }
        journal.claim(plan.manifestSha256) // Durable CREATE_NEW+fsync before the first mutation.
        profile.remove(plan) // Each exact signed file is rechecked and removed through held directory handles.
        keys.delete(plan.alias)
        return query(plan,keys,profile,journal)
    }
}

/** Only this finite signed graph can reach native descriptor-relative delete methods. */
internal class F73ProfileTree(private val parent: ()->Directory, private val run: String): F73OwnedCleanupPolicy.Profile {
    data class Stat(val key: String, val directory: Boolean, val regular: Boolean, val symlink: Boolean, val bytes: Long)
    interface Directory: AutoCloseable {
        fun names(): Set<String>
        fun stat(name: String): Stat
        fun directory(name: String): Directory
        fun hash(name: String, expected: Stat): String
        fun unlink(name: String, expected: Stat)
        fun rmdir(name: String, expected: Stat)
    }
    private fun leaf(path: String)=path.substringAfterLast('/')
    private fun children(plan: F73OwnedCleanupPolicy.Plan,prefix: String)=plan.inventory.filter {
        it.path.isNotEmpty() && it.path.substringBeforeLast('/',"")==prefix
    }
    override fun remaining(plan: F73OwnedCleanupPolicy.Plan, partial: Boolean): List<F73OwnedCleanupPolicy.Entry> {
        require(plan.run==run)
        return parent().use { holder ->
            if(run !in holder.names()) {require(partial);return@use emptyList()}
            val root=holder.stat(run);require(root.directory && !root.symlink)
            val actual=mutableListOf<F73OwnedCleanupPolicy.Entry>()
            fun walk(dir: Directory,prefix: String) {
                actual+=plan.inventory.single {it.path==prefix && it.directory}
                for(name in dir.names()) {
                    require(name.matches(Regex("[a-zA-Z0-9._-]+")) && name !in setOf(".",".."))
                    val approved=children(plan,prefix).singleOrNull {leaf(it.path)==name} ?: error("Unknown material: preserve profile")
                    val st=dir.stat(name);require(!st.symlink)
                    if(approved.directory) {require(st.directory);dir.directory(name).use {walk(it,approved.path)}}
                    else {require(st.regular && st.bytes==approved.bytes);require(dir.hash(name,st)==approved.sha256);actual+=approved}
                    require(dir.stat(name)==st)
                }
            }
            holder.directory(run).use {walk(it,"")};require(holder.stat(run)==root)
            if(!partial)require(actual.toSet()==plan.inventory.toSet())
            actual
        }
    }
    override fun remove(plan: F73OwnedCleanupPolicy.Plan) {
        require(remaining(plan,false).toSet()==plan.inventory.toSet())
        parent().use {holder ->
            val root=holder.stat(run);require(root.directory && !root.symlink)
            fun remove(dir: Directory,prefix: String) {
                val expected=children(plan,prefix);require(dir.names()==expected.map {leaf(it.path)}.toSet())
                for(entry in expected.sortedBy {it.path}) {
                    val name=leaf(entry.path);val st=dir.stat(name);require(!st.symlink)
                    if(entry.directory) {
                        require(st.directory);dir.directory(name).use {remove(it,entry.path)};dir.rmdir(name,st)
                    } else {
                        require(st.regular && st.bytes==entry.bytes && dir.hash(name,st)==entry.sha256)
                        dir.unlink(name,st)
                    }
                }
                require(dir.names().isEmpty())
            }
            holder.directory(run).use {remove(it,"")};holder.rmdir(run,root)
        }
    }
}
