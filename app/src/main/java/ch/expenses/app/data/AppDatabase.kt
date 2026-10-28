package ch.expenses.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Receipt::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun receipts(): ReceiptDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "receipts.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipts ADD COLUMN vat TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE receipts ADD COLUMN abroad INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
