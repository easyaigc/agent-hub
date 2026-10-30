package com.agenthub.android

import com.agenthub.core.CodexDiscoveryContract

/** Pre-POST intents are audit only: their presence proves neither submission nor a pairing outcome. */
internal object NativeMigrationPrivateClaims {
    const val ACTIVE_PREFIX="codex-private-claim:"
    const val RECORD="migration-private-claims-v1"
    const val TEXT="migration-private-claim-text-v1:"
    const val BINARY="migration-private-claim-binary-v1:"
    const val MAX_INTENTS=128
    const val MAX_INTENT_BYTES=8192
    const val MAX_ORIGINAL_BYTES=512*1024
    private val archive=NativeMigrationInertArchive(RECORD,TEXT,BINARY,"agent-hub/inert-private-claims/v1",::validateOriginal)
    fun belongsToFamily(name: String)=name.startsWith("codex-private-claim")||name.startsWith("migration-private-claim")
    private fun requireKnownFamilyNames(names: Set<String>) {
        require(names.none {belongsToFamily(it)&&it!=RECORD&&!it.startsWith(ACTIVE_PREFIX)&&!it.startsWith(TEXT)&&!it.startsWith(BINARY)})
    }

    /** Reuses the producer's named domain contract and endpoint parser; it performs no host read. */
    fun validateOriginal(text: Map<String,String>,binary: Map<String,NativeMigrationBlob>) {
        require(binary.isEmpty()&&text.size in 1..MAX_INTENTS)
        var total=0L
        for(name in text.keys) {
            require(name.matches(Regex("codex-private-claim:[a-f0-9]{64}")))
            if(text is NativeMigrationTextMap)require(text.frame(name).size<=MAX_INTENT_BYTES)
            val raw=text.getValue(name);require(raw.length<=MAX_INTENT_BYTES)
            val bytes=NativeMigrationTextFrames.bytes(raw)
            try {
                require(bytes.size<=MAX_INTENT_BYTES);total+=bytes.size;require(total<=MAX_ORIGINAL_BYTES)
                require(name==ACTIVE_PREFIX+NativeMigrationInertArchive.sha(bytes))
                val root=StrictMigrationJson.parse(bytes)
                require(root.keys().asSequence().toSet()==setOf("claim","endpoint","fingerprint"))
                val claim=root.getJSONObject("claim")
                require(claim.get("version")==1&&CodexDiscoveryContract.validateNamed(claim.toString(),"privateClaim")==null)
                val endpoint=root.get("endpoint") as String
                require(CodexEndpoints.local(endpoint)==endpoint)
                require((root.get("fingerprint") as String).matches(Regex("[a-f0-9]{64}")))
            }finally {bytes.fill(0)}
        }
    }

    /** No guessed legacy hashes: undiscoverable originals remain an inventory error. */
    fun legacyNames(read: (String)->String?): Set<String> = setOf(RECORD)+read(RECORD)?.let(archive::legacyNames).orEmpty()

    fun validatePortable(text: Map<String,String>,binary: Map<String,NativeMigrationBlob>): Set<String> {
        requireKnownFamilyNames(text.keys+binary.keys)
        require((text.keys+binary.keys).none {it.startsWith(ACTIVE_PREFIX)})
        val graph=archive.validate(text,binary.filterKeys {it.startsWith(BINARY)})
        require(graph.binaryNames.isEmpty())
        return graph.textNames
    }

    /** Removes active names only after exact validation; all unrelated records remain for the root graph. */
    fun projectSource(source: NativeMigrationStreamRecords): NativeMigrationStreamRecords {
        requireKnownFamilyNames(source.text.keys+source.binary.keys)
        require(source.binary.keys.none {it.startsWith(ACTIVE_PREFIX)})
        val names=source.text.keys.filter {it.startsWith(ACTIVE_PREFIX)}.toSet()
        val retained=NativeMigrationTextMap.copy(source.text);retained.forget(names)
        val portable=source.copy(text=retained)
        validatePortable(portable.text,portable.binary)
        if(names.isEmpty())return source
        require(names.size<=MAX_INTENTS)
        val originals=NativeMigrationTextMap.copy(emptyMap())
        for(name in names)originals.alias(source.text,name,name)
        return archive.append(portable,NativeMigrationStreamRecords(originals,emptyMap(),4)).also {
            validatePortable(it.text,it.binary)
        }
    }

    /** Destination originals are unioned as inert aliases, never restored to the producer's active prefix. */
    fun merge(source: NativeMigrationStreamRecords,destination: NativeMigrationStreamRecords): NativeMigrationStreamRecords {
        validatePortable(source.text,source.binary)
        return archive.merge(source,projectSource(destination)).also {validatePortable(it.text,it.binary)}
    }
    fun read(storage: NativeSessionStorage)=archive.read(storage)
    fun text(storage: NativeSessionStorage,original: NativeMigrationInertArchive.Original,index: Int)=archive.text(storage,original,index)
}
