package com.agenthub.android

/** Semantic component only. Does not certify directory inventory, grant closure or producer ownership. */
internal object NativeMigrationSelectionGraph {
    data class Audit(val originalJournal: NativeMigrationSelectionJournal.Original?,val expiredConsumedIds: Set<String>)
    fun validate(kind: NativeMigrationSelectionJournal.Kind,journal: NativeMigrationStreamRecords?,
        payloads: NativeMigrationStreamRecords?=null,outputs: NativeMigrationStreamRecords?=null,rawNames: Set<String> = emptySet()): Audit {
        journal?.let {require(it.binary.isEmpty()&&it.text.keys.all {name->name==NativeMigrationSelectionJournal.RECORD})}
        // Camera output claims/raw writers require a separate proved owner/read boundary, still closed.
        require(outputs==null&&rawNames.isEmpty()) {"Camera output originals are not yet supported"}
        val original=journal?.text?.get(NativeMigrationSelectionJournal.RECORD)?.let {NativeMigrationSelectionJournal.decode(it,kind)}
        val payload=payloads?:return Audit(original,emptySet())
        NativeMigrationInboxGraph.validate(payload.text,payload.binary) // One common payload graph validator.
        requireNotNull(original) {"Selection payload has no original journal"}
        val requests=original.requests.associateBy {it.id};val expired=mutableSetOf<String>()
        val index=StrictMigrationJson.parse(payload.text.getValue(NativeMigrationInboxGraph.INDEX).toByteArray(Charsets.UTF_8))
        val version=index.get("version") as Int;val rows=index.getJSONArray("records")
        for(row in rows.objects()) {
            val id=row.get("id") as String;val phase=row.get("phase") as String;val request=requests[id]
            if(request==null) {
                // The journal expires closed rows at32; the inbox retains consumed receipts/residue.
                require(phase=="consumed") {"Nonterminal selection payload has no journal request"}
                expired.add(id);continue
            }
            require(request.sources.isNotEmpty()) {"Selection payload precedes original source selection"}
            val slots=row.getJSONArray("slots")
            require(slots.length()==request.sources.size||phase=="consumed"&&slots.length()==0)
            val maximum=if(version==1)10 else row.get("maximum") as Int
            require(maximum==request.maximum)
            if(!row.isNull("binding")) {
                val binding=row.getJSONObject("binding");require(binding.getString("key")==request.key&&binding.getString("generation")==request.generation)
            }
            require(request.phase !in setOf("consumed","retired")||phase=="consumed")
        }
        return Audit(original,expired)
    }
}
