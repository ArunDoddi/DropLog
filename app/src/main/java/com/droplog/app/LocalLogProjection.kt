package com.droplog.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

// Builds employee state from confirmed data plus durable pending logs, without claiming server confirmation.
object LocalLogProjection {
    fun project(uid: String,data: JSONObject,queue: List<JSONObject>,today: String=Instant.now().atZone(ZoneId.of("America/Chicago")).toLocalDate().toString()): JSONObject {
        val meals=data.optJSONArray("meals")?:JSONArray()
        val filtered=JSONArray();for(i in 0 until meals.length()){val m=meals.getJSONObject(i);if(m.optString("user_id")==uid&&m.optString("day")==today)filtered.put(m)}
        val cash=data.optJSONArray("cash")?:JSONArray();val events=data.optJSONArray("events")?:JSONArray()
        for(record in queue.filter {it.getString("uid")==uid}) {
            val body=record.getJSONObject("body");val stamp=record.getString("created");val entry=JSONObject(body.toString()).put("id",body.getString("request_id")).put("user_id",uid).put("name",record.getString("name")).put("created",stamp).put("pending",true).put("day",Instant.parse(stamp).atZone(ZoneId.of("America/Chicago")).toLocalDate().toString())
            when(record.getString("operation")) {
                "meal" -> {if(Instant.parse(stamp).atZone(ZoneId.of("America/Chicago")).toLocalDate().toString()==today&&!containsId(filtered,body.getString("request_id")))filtered.put(entry)}
                "cash" -> if(!containsId(cash,body.getString("request_id")))cash.put(entry.put("cents",(body.getString("amount").toBigDecimal()*100.toBigDecimal()).toInt()))
                "equipment" -> {
                    // A cloud response might arrive before its queue acknowledgement; never apply twice.
                    if(containsId(events,body.getString("request_id")))continue
                    val shift=data.optJSONObject("shift")?:JSONObject()
                    val action=body.getString("action")
                    if(action=="start"){shift.put("id",body.getString("request_id")).put("shift_id",body.getString("request_id")).put("location",body.getString("location"))}
                    if(action=="start"||action=="in"){shift.put("radio",body.getString("radio")).put("keys",body.getString("keys"))}
                    shift.put("state",when(action){"start","in"->"in";"end"->"end";else->"out"})
                    data.put("shift",shift);events.put(entry)
                }
            }
        }
        return data.put("meals",filtered).put("cash",cash).put("events",events)
    }
    private fun containsId(array: JSONArray,id: String)=(0 until array.length()).any {array.getJSONObject(it).optString("id")==id}
}
