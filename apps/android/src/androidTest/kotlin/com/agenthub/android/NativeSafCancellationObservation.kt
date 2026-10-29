package com.agenthub.android

/** Input fencing for one actual product picker. WAIT never authorizes another launch or callback. */
internal object NativeSafCancellationObservation {
    enum class Caller { ORIGINAL, CANCELLED, UNKNOWN }
    enum class Next { BACK, WAIT, COMPLETE, STOP_OWNER, STOP_CALLER }
    fun next(owner: String?, app: String, docs: String, caller: Caller, acceptedBacks: Int, returnedToApp: Boolean=false): Next {
        require(app.isNotBlank()&&docs.isNotBlank()&&app!=docs&&acceptedBacks in 0..3)
        if(owner!=null&&owner!=app&&owner!=docs)return Next.STOP_OWNER
        if(caller==Caller.UNKNOWN)return Next.STOP_CALLER
        if(owner==app)return if(caller==Caller.CANCELLED)Next.COMPLETE else Next.WAIT
        if(!returnedToApp&&owner==docs&&caller==Caller.ORIGINAL&&acceptedBacks<3)return Next.BACK
        return Next.WAIT
    }
}
