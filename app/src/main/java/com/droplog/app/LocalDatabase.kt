package com.droplog.app

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONObject

// Confirmed cloud records mirrored on the tablet. Firestore remains authoritative.
@Entity(tableName = "cloud_records")
data class CloudRecord(@PrimaryKey val key: String, val collection: String, val json: String, val mirroredAt: Long)

@Dao
interface CloudRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(records: List<CloudRecord>)
    @Query("DELETE FROM cloud_records WHERE `key` = :key")
    suspend fun delete(key: String)
    @Query("SELECT * FROM cloud_records WHERE collection = :collection ORDER BY mirroredAt DESC")
    suspend fun records(collection: String): List<CloudRecord>
}

@Database(entities = [CloudRecord::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun records(): CloudRecordDao
    companion object {
        lateinit var instance: LocalDatabase
            private set
        fun initialize(context: Context) {
            if(::instance.isInitialized)return
            instance = Room.databaseBuilder(context.applicationContext, LocalDatabase::class.java, "droplog-local.db").build()
        }
    }
}

object LocalMirror {
    suspend fun dashboard(data: JSONObject) {
        val records = mutableListOf<CloudRecord>()
        for (collection in listOf("cash", "events", "active")) {
            val array = data.optJSONArray(collection) ?: continue
            for (i in 0 until array.length()) {
                val value = array.getJSONObject(i)
                records.add(CloudRecord("$collection/${value.getString("id")}", collection, value.toString(), System.currentTimeMillis()))
            }
        }
        data.optJSONObject("user")?.let { user -> records.add(CloudRecord("users/${user.getString("id")}", "users", user.toString(), System.currentTimeMillis())) }
        LocalDatabase.instance.records().put(records)
    }
    suspend fun settings(data: JSONObject) {
        LocalDatabase.instance.records().put(listOf(CloudRecord("settings/report", "settings", data.toString(), System.currentTimeMillis())))
    }
    suspend fun users(data: JSONObject) {
        val users=data.optJSONArray("users") ?: return
        LocalDatabase.instance.records().put((0 until users.length()).map { i -> val u=users.getJSONObject(i); CloudRecord("users/${u.getString("id")}","users",u.toString(),System.currentTimeMillis()) })
    }
}
