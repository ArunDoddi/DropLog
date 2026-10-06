package com.droplog.app

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import org.json.JSONArray

class LocalLogProjectionTest {
    private fun record(operation: String,id: String,action: String="",uid: String="a",time: String="2026-10-03T17:00:00Z")=JSONObject().put("uid",uid).put("name","Test Employee").put("created",time).put("operation",operation).put("body",JSONObject().put("request_id",id).put("action",action).put("radio","7").put("keys","8").put("location","Hotel").put("amount","12.50").put("meal","Lunch"))
    @Test fun equipmentStateSurvivesOfflineReentryAndAcknowledgement() {
        val start=record("equipment","equipment-start","start");val out=record("equipment","equipment-out","out")
        val first=LocalLogProjection.project("a",JSONObject(),listOf(start,out),"2026-10-03")
        assertEquals("out",first.getJSONObject("shift").getString("state"));assertEquals("7",first.getJSONObject("shift").getString("radio"))
        // A stored baseline can already contain the acknowledged Start. Replay must not apply it twice.
        val acknowledged=LocalLogProjection.project("a",JSONObject(),listOf(start),"2026-10-03")
        val second=LocalLogProjection.project("a",acknowledged,listOf(start,out),"2026-10-03")
        assertEquals(2,second.getJSONArray("events").length());assertEquals("out",second.getJSONObject("shift").getString("state"))
        val ended=LocalLogProjection.project("a",second,listOf(record("equipment","equipment-end","end"),record("equipment","equipment-undo","undo_end")),"2026-10-03")
        assertEquals("out",ended.getJSONObject("shift").getString("state"))
    }
    @Test fun mealsIncludePendingSignaturesWithoutDuplicateCountingAndResetOnCentralDay() {
        val meal=record("meal","meal-lunch")
        val state=LocalLogProjection.project("a",JSONObject(),listOf(meal),"2026-10-03")
        assertEquals(1,state.getJSONArray("meals").length());assertTrue(state.getJSONArray("meals").getJSONObject(0).getBoolean("pending"))
        val again=LocalLogProjection.project("a",state,listOf(meal),"2026-10-03")
        assertEquals(1,again.getJSONArray("meals").length())
        assertEquals(0,LocalLogProjection.project("a",again,listOf(meal),"2026-10-04").getJSONArray("meals").length())
        // 00:30 UTC is still the previous Central day.
        assertEquals(1,LocalLogProjection.project("a",JSONObject(),listOf(record("meal","late-dinner",time="2026-10-04T00:30:00Z")),"2026-10-03").getJSONArray("meals").length())
    }
    @Test fun queueIsIsolatedByEmployeeAndCashUsesExactCents() {
        val state=LocalLogProjection.project("a",JSONObject(),listOf(record("cash","cash-a"),record("meal","meal-b",uid="b")),"2026-10-03")
        assertEquals(1250,state.getJSONArray("cash").getJSONObject(0).getInt("cents"));assertEquals(0,state.getJSONArray("meals").length())
        assertEquals("2026-10-03T17:00:00Z",state.getJSONArray("cash").getJSONObject(0).getString("created"))
    }
}
