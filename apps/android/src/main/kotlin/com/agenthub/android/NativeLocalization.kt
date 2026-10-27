package com.agenthub.android

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Platform display preference only; never part of a machine or command identity. */
internal enum class NativeAppLanguage(val value: String) {
    SYSTEM("system"),CHINESE("zh-Hans"),ENGLISH("en");
    fun resolved(preferred: List<String>): String {
        if(this!=SYSTEM)return value
        for(language in preferred.map { it.lowercase(Locale.ROOT) }) {
            if(language.startsWith("zh"))return CHINESE.value
            if(language.startsWith("en"))return ENGLISH.value
        }
        return ENGLISH.value
    }
    companion object { fun from(value: String)=entries.firstOrNull { it.value==value } ?: SYSTEM }
}

/** Explicit app-owned keys only. Message bodies, names and paths are never looked up. */
internal class NativeL10n(context: Context,selection: String,configuration: Configuration=context.resources.configuration) {
    val language=NativeAppLanguage.from(selection).resolved((0 until configuration.locales.size()).map { configuration.locales[it].toLanguageTag() })
    private val resources=context.createConfigurationContext(Configuration(configuration).apply { setLocale(Locale.forLanguageTag(language)) }).resources
    private val packageName=context.packageName
    val privacyPolicyUrl get()="https://51easyai.com/agent-hub/"+if(language=="en")"privacy-en.html" else "privacy.html"
    val supportUrl get()="https://51easyai.com/agent-hub/"+if(language=="en")"support-en.html" else "support.html"
    internal fun catalogValue(key: String): String {
        val id=identifiers.getOrPut(packageName to key) {
            val name="product_"+MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
            resources.getIdentifier(name,"string",packageName)
        }
        return if(id==0)key else resources.getString(id)
    }
    fun tr(key: String,vararg arguments: Any?)=render(catalogValue(key),arguments.map { it.toString() })
    companion object {
        private val identifiers=ConcurrentHashMap<Pair<String,String>,Int>()
        // A small, non-executable format grammar shared by the two catalogs.
        // An argument containing %@, percent signs or another key stays literal.
        internal fun render(format: String,arguments: List<String>): String=buildString {
            var index=0;var argument=0
            while(index<format.length) {
                if(format[index]=='%'&&index+1<format.length&&format[index+1] in listOf('%','@')) {
                    if(format[index+1]=='@')append(arguments.getOrNull(argument++) ?: "%@") else append('%')
                    index+=2
                }else append(format[index++])
            }
        }
    }
}

internal val LocalProductText=staticCompositionLocalOf<NativeL10n?> { null }
@Composable internal fun currentProductText(): NativeL10n {
    val inherited=LocalProductText.current
    val context=LocalContext.current;val configuration=LocalConfiguration.current
    return inherited ?: remember(context,configuration) { NativeL10n(context,"system",configuration) }
}
/** Call sites pass reviewed literals; arbitrary rendered text is never intercepted. */
@Composable internal fun productText(key: String,vararg arguments: Any?)=currentProductText().tr(key,*arguments)
@Composable internal fun ProductTextProvider(selection: String,content: @Composable ()->Unit) {
    val context=LocalContext.current;val configuration=LocalConfiguration.current
    val text=remember(context,configuration,selection) { NativeL10n(context,selection,configuration) }
    CompositionLocalProvider(LocalProductText provides text,content=content)
}
