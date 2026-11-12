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
    @ColumnInfo(defaultValue = "0") val costPrice: Long = 0,
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
    @ColumnInfo(defaultValue = "DINE_IN") val orderType: String = "DINE_IN",
    val tableNo: String? = null,
    @ColumnInfo(defaultValue = "0") val subtotal: Long = 0,
    @ColumnInfo(defaultValue = "0") val discount: Long = 0,
    val promoSummary: String? = null,
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
    @ColumnInfo(defaultValue = "0") val unitCost: Long = 0,
    val note: String? = null,
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

    @Query("SELECT COALESCE(SUM(total), 0) AS omzet, COUNT(*) AS trx FROM orders WHERE createdAtMs >= :fromMs AND status = 'PAID' AND outletId = :outletId")
    abstract fun todayTotals(fromMs: Long, outletId: String): Flow<TodayTotals>

    @Query("SELECT COALESCE(SUM(i.unitCost * i.qty), 0) FROM order_items i JOIN orders o ON o.id = i.orderId WHERE o.createdAtMs >= :fromMs AND o.status = 'PAID' AND o.outletId = :outletId")
    abstract fun todayCost(fromMs: Long, outletId: String): Flow<Double>

    @Query("SELECT i.productName AS name, SUM(i.qty) AS qty FROM order_items i JOIN orders o ON o.id = i.orderId WHERE o.createdAtMs >= :fromMs AND o.status = 'PAID' AND o.outletId = :outletId GROUP BY i.productName ORDER BY qty DESC LIMIT 1")
    abstract fun topToday(fromMs: Long, outletId: String): Flow<TopProduct?>

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
    version = 3,
    exportSchema = true,
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun dao(): PosDao

    companion object {
        @Volatile
        private var instance: PosDatabase? = null

        fun get(context: Context): PosDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, PosDatabase::class.java, "pos.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

data class TodayTotals(val omzet: Long, val trx: Int)
data class TopProduct(val name: String, val qty: Double)

/** v1.2: HPP, order type/table, subtotal/discount/promo, item cost and note. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN costPrice INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN orderType TEXT NOT NULL DEFAULT 'DINE_IN'")
        db.execSQL("ALTER TABLE orders ADD COLUMN tableNo TEXT")
        db.execSQL("ALTER TABLE orders ADD COLUMN subtotal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN discount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE orders ADD COLUMN promoSummary TEXT")
        db.execSQL("ALTER TABLE order_items ADD COLUMN unitCost INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE order_items ADD COLUMN note TEXT")
    }
}
