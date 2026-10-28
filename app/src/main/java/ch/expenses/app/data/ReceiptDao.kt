package ch.expenses.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {
    /**
     * Receipts whose date (or, if undated, creation day) falls in [fromEpochDay, toEpochDay].
     * Undated receipts are kept visible in the month they were photographed so they aren't lost.
     */
    @Query(
        """
        SELECT * FROM receipts
        WHERE COALESCE(dateEpochDay, createdAt / 86400000) BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY COALESCE(dateEpochDay, createdAt / 86400000) DESC, id DESC
        """
    )
    fun observeRange(fromEpochDay: Long, toEpochDay: Long): Flow<List<Receipt>>

    @Query(
        """
        SELECT * FROM receipts
        WHERE COALESCE(dateEpochDay, createdAt / 86400000) BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY COALESCE(dateEpochDay, createdAt / 86400000) ASC, id ASC
        """
    )
    suspend fun getRange(fromEpochDay: Long, toEpochDay: Long): List<Receipt>

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun get(id: Long): Receipt?

    @Upsert
    suspend fun upsert(receipt: Receipt): Long

    @Delete
    suspend fun delete(receipt: Receipt)
}
