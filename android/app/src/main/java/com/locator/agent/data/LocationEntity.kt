package com.locator.agent.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "positions")
data class LocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lon: Double,
    val accuracy: Double?,
    val speed: Double?,
    val altitude: Double?,
    val bearing: Double?,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int?,
    val charging: Boolean?,
    val source: String,          // FUSED | GPS | NETWORK
    val provider: String,        // fused | gps | network
    @ColumnInfo(name = "cell_wifi") val cellWifi: String?, // JSON Precision+ (opcional)
    @ColumnInfo(name = "recorded_at") val recordedAt: Long, // epoch millis
    val sent: Boolean = false
)

@Dao
interface LocationDao {

    @Insert
    suspend fun insert(entity: LocationEntity): Long

    @Query("SELECT * FROM positions WHERE sent = 0 ORDER BY recorded_at ASC LIMIT :limit")
    suspend fun pending(limit: Int = 50): List<LocationEntity>

    @Query("UPDATE positions SET sent = 1 WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>)

    @Query("DELETE FROM positions WHERE sent = 1 AND recorded_at < :before")
    suspend fun pruneSent(before: Long): Int

    @Query("SELECT COUNT(*) FROM positions WHERE sent = 0")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM positions")
    suspend fun totalCount(): Int

    /** Borra TODO el buffer local (al desvincular el dispositivo). */
    @Query("DELETE FROM positions")
    suspend fun clearAll(): Int
}
