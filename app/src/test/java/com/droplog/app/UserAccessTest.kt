package com.droplog.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserAccessTest {
    @Test fun hotelDutyChoicesDisableTheConflictingDepartment() {
        assertFalse(canSelectHotelDuty(listOf("hotel_cash"),"equipment"))
        assertFalse(canSelectHotelDuty(listOf("equipment"),"hotel_cash"))
        assertTrue(canSelectHotelDuty(emptyList(),"hotel_cash"))
        assertTrue(canSelectHotelDuty(emptyList(),"equipment"))
        assertTrue(canSelectHotelDuty(listOf("hotel_cash"),"hotel_cash"))
    }
    @Test fun restaurantAndBarAccessAreIndependent() {
        val user=JSONObject().put("role","employee").put("separate_outlets",true).put("duties",JSONArray(listOf("bar_cash")))
        assertEquals(listOf("Bar"),cashOutlets(user))
        user.put("duties",JSONArray(listOf("restaurant_cash")))
        assertEquals(listOf("Restaurant"),cashOutlets(user))
        user.put("duties",JSONArray(listOf("restaurant_cash","bar_cash")))
        assertEquals(listOf("Restaurant","Bar"),cashOutlets(user))
    }
    @Test fun legacyRestaurantProfilesKeepBothOutletsUntilEdited() {
        val user=JSONObject().put("role","employee").put("duties",JSONArray(listOf("restaurant_cash")))
        assertEquals(listOf("Restaurant","Bar"),cashOutlets(user))
        user.put("cash_outlets",JSONArray(listOf("Restaurant")))
        assertEquals(listOf("Restaurant"),cashOutlets(user))
    }
    @Test fun removingLocationRemovesItsDuties() {
        assertEquals(listOf("bar_cash"),assignedDuties(listOf("Restaurant"),listOf("hotel_cash","equipment","bar_cash")))
        assertEquals(listOf("equipment"),assignedDuties(listOf("Hotel"),listOf("restaurant_cash","bar_cash","equipment")))
    }
}
