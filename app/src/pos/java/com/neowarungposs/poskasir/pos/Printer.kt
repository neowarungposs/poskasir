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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.neowarungposs.poskasir.core.Outlet
import java.io.ByteArrayOutputStream
import java.io.File
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
        outlet: Outlet?,
        order: OrderEntity,
        items: List<OrderItemEntity>,
        width: Int,
        logo: File? = null,
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
        fun wrap(text: String) = text.lines().forEach { l -> l.chunked(width).ifEmpty { listOf("") }.forEach { line(it) } }
        val dash = "-".repeat(width)

        raw(INIT); raw(ALIGN_CENTER)
        if (outlet?.receiptShowLogo == true && logo != null && logo.exists()) {
            runCatching { raw(logoRaster(logo, if (width >= 48) 576 else 384)) }
        }
        raw(BOLD_ON); raw(DOUBLE_ON); line(business.take(width / 2)); raw(DOUBLE_OFF); raw(BOLD_OFF)
        outlet?.name?.takeIf { it.isNotBlank() }?.let { line(it.take(width)) }
        outlet?.address?.takeIf { it.isNotBlank() }?.let { wrap(it) }
        outlet?.phone?.takeIf { it.isNotBlank() }?.let { line("Telp $it".take(width)) }
        outlet?.receiptHeader?.takeIf { it.isNotBlank() }?.let { wrap(it) }
        raw(ALIGN_LEFT)
        line(dash)
        line(order.invoiceNo)
        line(formatDateTime(order.createdAtMs))
        line(if (order.orderType == "TAKEAWAY") "Bungkus" else "Makan di tempat" + (order.tableNo?.let { " - Meja $it" } ?: ""))
        if (order.status == "VOIDED") { raw(BOLD_ON); line("*** VOID ***"); raw(BOLD_OFF) }
        line(dash)
        items.forEach { i ->
            line(i.productName.take(width))
            pair("  ${qty(i.qty)} x ${rupiah(i.unitPrice)}", rupiah(i.subtotal))
            i.note?.takeIf { it.isNotBlank() }?.let { line("  * $it".take(width)) }
        }
        line(dash)
        if (order.discount > 0) {
            pair("Subtotal", rupiah(order.subtotal))
            order.promoSummary?.split("; ")?.forEach { p ->
                val name = p.substringBeforeLast(" -")
                val amt = p.substringAfterLast(" -").toLongOrNull()
                pair(name.take(width - 12), amt?.let { "-" + rupiah(it) } ?: "")
            } ?: pair("Diskon", "-" + rupiah(order.discount))
        }
        raw(BOLD_ON); pair("TOTAL", rupiah(order.total)); raw(BOLD_OFF)
        pair(method(order.paymentMethod), order.cashReceived?.let { rupiah(it) } ?: "")
        order.cashReceived?.let { pair("Kembali", rupiah(it - order.total)) }
        line(dash)
        raw(ALIGN_CENTER)
        val footer = outlet?.receiptFooter?.takeIf { it.isNotBlank() } ?: "Terima kasih!"
        wrap(footer)
        line(); line(); line()
        raw(CUT)
        return out.toByteArray()
    }

    /** ESC/POS raster image (GS v 0), dithered to 1-bit, max width in dots. */
    private fun logoRaster(file: File, maxDots: Int): ByteArray {
        val src = BitmapFactory.decodeFile(file.absolutePath) ?: return ByteArray(0)
        val targetW = minOf(maxDots / 2, src.width).coerceAtLeast(8) / 8 * 8
        val targetH = (src.height * targetW.toFloat() / src.width).toInt().coerceIn(1, 240)
        val bmp = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        val bytesPerRow = targetW / 8
        val data = ByteArray(bytesPerRow * targetH)
        for (y in 0 until targetH) for (x in 0 until targetW) {
            val c = bmp.getPixel(x, y)
            val a = (c ushr 24) and 0xff
            val lum = (((c shr 16) and 0xff) * 299 + ((c shr 8) and 0xff) * 587 + (c and 0xff) * 114) / 1000
            if (a > 128 && lum < 140) data[y * bytesPerRow + x / 8] = (data[y * bytesPerRow + x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1D, 0x76, 0x30, 0, (bytesPerRow and 0xff).toByte(), (bytesPerRow shr 8).toByte(), (targetH and 0xff).toByte(), (targetH shr 8).toByte()))
        out.write(data)
        out.write(0x0A)
        return out.toByteArray()
    }

    private fun method(m: String) = when (m) { "CASH" -> "Tunai"; "QRIS" -> "QRIS"; else -> "Lainnya" }
    private fun qty(q: Double) = if (q % 1.0 == 0.0) q.toLong().toString() else q.toString()
    private fun ascii(s: String) = s.map { if (it.code in 32..126) it else '?' }.joinToString("")
}
