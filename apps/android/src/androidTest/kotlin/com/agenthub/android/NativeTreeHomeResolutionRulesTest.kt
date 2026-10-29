package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Pure resolver boundary only; no PackageManager/shell/AX or native device observation. */
class NativeTreeHomeResolutionRulesTest {
    private val app="com.easyai.agenthub.android.acceptance"
    private val real=NativeTreeHomeResolution.Candidate("example.user.home","example.user.home.HomeActivity",10001,true,true)
    private fun accepted(value: NativeTreeHomeResolution.Candidate?,visible: List<NativeTreeHomeResolution.Candidate> = listOf(real))=
        NativeTreeHomeResolution.accepted(value,visible,app,10002)
    @Test fun exactVisibleEnabledDefaultHomePassesWithoutALauncherAllowlist() { assertTrue(accepted(real)) }
    @Test fun settingsNeverBecomesTrustedHomeEvenWhenItIsVisibleAndResolved() {
        for(name in listOf("com.android.settings.FallbackHome","com.android.settings.OtherHome")) {
            val value=real.copy(packageName="com.android.settings",activityName=name)
            assertFalse(accepted(value,listOf(value)))
        }
    }
    @Test fun fallbackAndResolverOrChooserComponentsCannotPass() {
        for(name in listOf("example.system.FallbackHome","com.android.internal.app.ResolverActivity","com.android.internal.app.ChooserActivity")) {
            val value=real.copy(activityName=name);assertFalse(accepted(value,listOf(value)))
        }
        val framework=real.copy(packageName="android");assertFalse(accepted(framework,listOf(framework)))
    }
    @Test fun resolverMustMatchTheExactVisibleComponentAndUid() {
        assertFalse(accepted(real,listOf(real.copy(activityName="example.user.home.AnotherActivity"))))
        assertFalse(accepted(real,listOf(real.copy(uid=10003))))
        assertFalse(accepted(real.copy(packageName="example.other.home")))
    }
    @Test fun disabledUnexportedAndAppOwnedComponentsCannotPass() {
        for(value in listOf(real.copy(enabled=false),real.copy(exported=false),real.copy(uid=10002),
            real.copy(packageName=app),real.copy(packageName="$app.test"),real.copy(uid=-1)))assertFalse(accepted(value,listOf(value)))
    }
    @Test fun absentEmptyOrUnboundedVisibilityCannotFabricateAHome() {
        assertFalse(accepted(null));assertFalse(accepted(real,emptyList()))
        assertFalse(accepted(real,List(17){real}));assertFalse(accepted(real.copy(activityName="")))
    }
}
