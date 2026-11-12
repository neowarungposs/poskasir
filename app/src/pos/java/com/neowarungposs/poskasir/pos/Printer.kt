package com.neowarungposs.poskasir.pos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.neowarungposs.poskasir.core.formatDateTime
import com.neowarungposs.poskasir.core.rupiah
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

data class PairedPrinter(val name: String, val address: String)

/** ESC/POS printing over Bluetooth SPP to an already-paired printer (Section 10). */
object BluetoothPrinter {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedPrinters(context: Context): List<PairedPrinter> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return try {
            adapter.bondedDevices.map { PairedPrinter(it.name ?: it.address, it.address) }.sortedBy { it.name }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun print(context: Context, address: String, data: ByteArray) = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) throw IllegalStateException("Izin Bluetooth belum diberikan")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("HP ini tidak punya Bluetooth")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth mati. Nyalakan dulu.")
        val device = adapter.getRemoteDevice(address)
        val socket = try {
            device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: Exception) {
            throw IllegalStateException("Printer tidak terhubung. Pastikan printer menyala dan dekat.")
        }
        try {
            socket.outputStream.write(data)
            socket.outputStream.flush()
            delay(400) // let the printer drain its buffer before closing
        } catch (e: Exception) {
            throw IllegalStateException("Gagal mengirim ke printer: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }
}

/** Builds an ESC/POS receipt. [width] = characters per line: 32 for 58 mm, 48 for 80 mm. */
object ReceiptBuilder {
    private val INIT = byteArrayOf(0x1B, 0x40)
    private val ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0)
    private val ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 1)
    private val BOLD_ON = byteArrayOf(0x1B, 0x45, 1)
    private val BOLD_OFF = byteArrayOf(0x1B, 0x45, 0)
    private val DOUBLE_ON = byteArrayOf(0x1D, 0x21, 0x11)
    private val DOUBLE_OFF = byteArrayOf(0x1D, 0x21, 0)
    private val CUT = byteArrayOf(0x1D, 0x56, 0x42, 0)

    fun build(
        business: String,
        outlet: String,
        order: OrderEntity,
        items: List<OrderItemEntity>,
        width: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun raw(b: ByteArray) = out.write(b)
        fun line(text: String = "") = out.write((ascii(text) + "\n").toByteArray(Charsets.US_ASCII))
        fun pair(left: String, right: String) {
            val l = ascii(left)
            val r = ascii(right)
            if (l.length + r.length + 1 <= width) line(l + " ".repeat(width - l.length - r.length) + r)
            else { line(l.take(width)); line(" ".repeat(maxOf(0, width - r.length)) + r) }
        }
        val dash = "-".repeat(width)

        raw(INIT); raw(ALIGN_CENTER)
        raw(BOLD_ON); raw(DOUBLE_ON); line(business.take(width / 2)); raw(DOUBLE_OFF); raw(BOLD_OFF)
        if (outlet.isNotBlank()) line(outlet.take(width))
        raw(ALIGN_LEFT)
        line(dash)
        line(order.invoiceNo)
        line(formatDateTime(order.createdAtMs))
        if (order.status == "VOIDED") { raw(BOLD_ON); line("*** VOID ***"); raw(BOLD_OFF) }
        line(dash)
        items.forEach { i ->
            line(i.productName.take(width))
            pair("  ${qty(i.qty)} x ${rupiah(i.unitPrice)}", rupiah(i.subtotal))
        }
        line(dash)
        raw(BOLD_ON); pair("TOTAL", rupiah(order.total)); raw(BOLD_OFF)
        pair(method(order.paymentMethod), order.cashReceived?.let { rupiah(it) } ?: "")
        order.cashReceived?.let { pair("Kembali", rupiah(it - order.total)) }
        line(dash)
        raw(ALIGN_CENTER); line("Terima kasih!")
        line(); line(); line()
        raw(CUT)
        return out.toByteArray()
    }

    private fun method(m: String) = when (m) { "CASH" -> "Tunai"; "QRIS" -> "QRIS"; else -> "Lainnya" }
    private fun qty(q: Double) = if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
    private fun ascii(s: String) = s.map { if (it.code in 32..126) it else '?' }.joinToString("")
}
