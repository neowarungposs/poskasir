package com.neowarungposs.poskasir.pos

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val id: String,
    val outletId: String?,
    val name: String,
    val category: String?,
    val price: Long,
    val isActive: Boolean,
    val deleted: Boolean,
    val updatedAt: String?,
    val imagePath: String? = null,
)

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val outletId: String,
    val invoiceNo: String,
    val paymentMethod: String,
    val total: Long,
    val cashReceived: Long?,
    val createdAtMs: Long,
    val synced: Boolean,
    @ColumnInfo(defaultValue = "PAID") val status: String = "PAID",
)

@Entity(tableName = "order_items", indices = [Index("orderId")])
data class OrderItemEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val productId: String?,
    val productName: String,
    val unitPrice: Long,
    val qty: Double,
    val subtotal: Long,
)

@Entity(tableName = "kv")
data class KvEntity(@PrimaryKey val k: String, val v: String)

@Dao
abstract class PosDao {
    @Query("SELECT * FROM products WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    abstract fun productsFlow(): Flow<List<ProductEntity>>

    @Upsert
    abstract suspend fun upsertProducts(list: List<ProductEntity>)

    @Insert
    abstract suspend fun insertOrder(order: OrderEntity)

    @Insert
    abstract suspend fun insertItems(items: List<OrderItemEntity>)

    @Transaction
    open suspend fun saveOrder(order: OrderEntity, items: List<OrderItemEntity>) {
        insertOrder(order)
        insertItems(items)
    }

    @Query("UPDATE orders SET status = :status WHERE id IN (:ids)")
    abstract suspend fun setStatus(ids: List<String>, status: String)

    @Query("SELECT * FROM orders WHERE id = :id")
    abstract suspend fun order(id: String): OrderEntity?

    @Query("SELECT * FROM products WHERE id = :id")
    abstract suspend fun product(id: String): ProductEntity?

    @Query("SELECT * FROM orders ORDER BY createdAtMs DESC LIMIT 500")
    abstract fun ordersFlow(): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE synced = 0 ORDER BY createdAtMs LIMIT :limit")
    abstract suspend fun pendingOrders(limit: Int): List<OrderEntity>

    @Query("SELECT * FROM order_items WHERE orderId IN (:ids)")
    abstract suspend fun itemsFor(ids: List<String>): List<OrderItemEntity>

    @Query("SELECT * FROM order_items WHERE orderId = :orderId")
    abstract suspend fun items(orderId: String): List<OrderItemEntity>

    @Query("UPDATE orders SET synced = 1 WHERE id IN (:ids)")
    abstract suspend fun markSynced(ids: List<String>)

    @Query("SELECT COUNT(*) FROM orders WHERE synced = 0")
    abstract fun pendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM orders WHERE synced = 0")
    abstract suspend fun pendingCount(): Int

    @Query("SELECT v FROM kv WHERE k = :key")
    abstract suspend fun kv(key: String): String?

    @Upsert
    abstract suspend fun putKv(entry: KvEntity)

    @Query("DELETE FROM products")
    abstract suspend fun clearProducts()

    @Query("DELETE FROM order_items")
    abstract suspend fun clearItems()

    @Query("DELETE FROM orders")
    abstract suspend fun clearOrders()

    @Query("DELETE FROM kv")
    abstract suspend fun clearKv()

    @Transaction
    open suspend fun clearAll() {
        clearItems(); clearOrders(); clearProducts(); clearKv()
    }
}

@Database(
    entities = [ProductEntity::class, OrderEntity::class, OrderItemEntity::class, KvEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun dao(): PosDao

    companion object {
        @Volatile
        private var instance: PosDatabase? = null

        fun get(context: Context): PosDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PosDatabase::class.java, "pos.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}

/** v1.1: void status on orders, product photo path. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE orders ADD COLUMN status TEXT NOT NULL DEFAULT 'PAID'")
        db.execSQL("ALTER TABLE products ADD COLUMN imagePath TEXT")
    }
}
