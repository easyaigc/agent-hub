package com.agenthub.android

import android.util.JsonReader
import android.util.JsonToken
import com.agenthub.core.CodexHttpContract
import java.io.InputStream
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** Stream only the large message arrays. The same shared schemas validate every message and envelope. */
internal object NativeHistoryWire {
    fun history(input: InputStream,active: ()->Boolean): MobileHistory = read(input,active,::history)
    fun events(input: InputStream,active: ()->Boolean): EventBatch = read(input,active) { reader ->
        val snapshots=mutableListOf<MobileHistory>(); val envelope=JSONObject();reader.beginObject()
        while(reader.hasNext()) {
            val name=reader.nextName();require(!envelope.has(name)) { "重复的数据字段" }
            if(name!="events") envelope.put(name,value(reader)) else {
                val items=JSONArray();reader.beginArray()
                while(reader.hasNext()) {
                    val item=JSONObject();var snapshot: MobileHistory?=null;reader.beginObject()
                    while(reader.hasNext()) {
                        val key=reader.nextName();require(!item.has(key)) { "重复的数据字段" }
                        if(key=="snapshot") { snapshot=history(reader);item.put(key,JSONObject(snapshot.wire)) }
                        else item.put(key,value(reader))
                    }
                    reader.endObject();require(snapshot!=null);require(item.getString("threadId")==snapshot.threadId)
                    snapshots.add(snapshot);items.put(item)
                }
                reader.endArray();envelope.put(name,items)
            }
        }
        reader.endObject();CodexCommandCodec.validate(envelope,"events")
        require(snapshots.all { it.hostId==envelope.getString("hostId") }) { "事件电脑身份不匹配" }
        EventBatch(envelope.getString("hostId"),envelope.getString("epoch"),envelope.getLong("cursor"),envelope.getBoolean("reset"),
            envelope.booleanOrNull("ready"),envelope.optJSONArray("capabilities")?.strings(),snapshots)
    }
    private fun <T> read(input: InputStream,active: ()->Boolean,decode: (JsonReader)->T): T =
        JsonReader(NativeWireReader.reader(input,64*1024*1024,active)).use { reader ->
            reader.isLenient=false; val decoded=decode(reader);require(reader.peek()==JsonToken.END_DOCUMENT) { "电脑返回了多段数据" };decoded
        }
    private fun history(reader: JsonReader): MobileHistory {
        val header=JSONObject(); val messages=ArrayList<MobileMessage>();reader.beginObject()
        while(reader.hasNext()) {
            val name=reader.nextName();require(!header.has(name)) { "重复的数据字段" }
            if(name!="messages") header.put(name,value(reader)) else {
                reader.beginArray()
                while(reader.hasNext()) {
                    val message=value(reader) as? JSONObject ?: error("历史消息格式无效")
                    require(CodexHttpContract.validate(message.toString(),"message")==null) { "电脑历史消息格式无效" }
                    messages.add(HostReadCodec.message(message))
                }
                reader.endArray();header.put(name,JSONArray())
            }
        }
        reader.endObject();val page=HostReadCodec.history(header.toString())
        require(messages.all { m -> m.attachments.all { it.hostId==page.hostId && it.threadId==page.threadId } }) { "电脑或附件身份不匹配" }
        return page.copy(messages=messages)
    }
    private fun value(reader: JsonReader,depth: Int=0): Any {
        require(depth<128) { "电脑数据嵌套过深" }
        return when(reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                reader.beginObject();while(reader.hasNext()) { val name=reader.nextName();require(!has(name)) { "重复的数据字段" };put(name,value(reader,depth+1)) };reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                reader.beginArray();while(reader.hasNext()) put(value(reader,depth+1));reader.endArray()
            }
            JsonToken.STRING -> NativeHistoryStrings.share(reader.nextString())
            JsonToken.NUMBER -> BigDecimal(reader.nextString())
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull();JSONObject.NULL }
            else -> error("电脑数据格式无效")
        }
    }
}
