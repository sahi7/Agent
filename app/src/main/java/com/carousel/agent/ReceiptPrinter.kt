package com.carousel.agent

import android.content.Context
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import android.util.Log
import android.hardware.usb.UsbManager
import org.json.JSONObject
import java.io.IOException
import java.io.File
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dantsu.escposprinter.textparser.PrinterTextParserImg

class ReceiptPrinter(private val context: Context, private val config: PrinterConfig) {
    private var printer: EscPosPrinter? = null

    suspend fun connect() {
        try {
            when (config.connectionType) {
                "usb" -> {
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    val device = usbManager.deviceList.values.find {
                        it.vendorId == config.vendorId?.toInt(16) && it.productId == config.productId?.toInt(16)
                    }
                    if (device != null) {
                        val usbConnection = UsbConnection(usbManager, device)
                        printer = EscPosPrinter(usbConnection, 203, 48f, 32)
                    } else {
                        throw IOException("USB printer not found")
                    }
                }
                "network" -> {
                    val tcpConnection = TcpConnection(config.ipAddress, 9100)
                    printer = EscPosPrinter(tcpConnection, 203, 48f, 32)
                }
                else -> throw IOException("Unsupported connection type")
            }
        } catch (e: IOException) {
            Log.e("Printer", "Connect failed: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e("Printer", "Unexpected error: ${e.message}")
            throw e
        }
    }

    suspend fun printReceipt(context: Context, orderData: JSONObject) {
        try {
            printer?.let { p ->
                val sharedPrefs = PrinterUtils.getEncryptedAuthPrefs(context)

                // Get branch & device info
                val branchName = sharedPrefs.getString("branch_name", "Unknown Branch")
                val branchAddress = sharedPrefs.getString("branch_address", "No Address")
                val branchCurrency = sharedPrefs.getString("branch_currency", "USD")
                val branchTimezone = sharedPrefs.getString("branch_timezone", "N/A")
                val deviceName = sharedPrefs.getString("device_name", "Device")

                // Load saved branch logo
                val logoPath = sharedPrefs.getString("branch_logo_path", null)
                var imgHexString: String? = null

                if (!logoPath.isNullOrEmpty()) {
                    val file = File(logoPath)
                    if (file.exists()) {
                        var bmp: Bitmap? = BitmapFactory.decodeFile(file.absolutePath)
                        // library constraint: max height 256px for <img> tag (per README)
                        val maxHeight = 256
                        if (bmp != null && bmp.height > maxHeight) {
                            val scaledWidth = (bmp.width * (maxHeight.toFloat() / bmp.height)).toInt()
                            bmp = Bitmap.createScaledBitmap(bmp, scaledWidth, maxHeight, true)
                        }
                        if (bmp != null) {
                            // Convert Bitmap to hex string for the library.
                            // PrinterTextParserImg.bitmapToHexadecimalString(printer, bitmap [, gradient])
                            // the method expects the printer instance and a Bitmap.
                            imgHexString = PrinterTextParserImg.bitmapToHexadecimalString(p, bmp)
                            if (imgHexString.isNullOrEmpty()) {
                                imgHexString = null
                            }
                        }
                    }
                }

                // Build formatted receipt
                val formattedText = buildString {
                    // Logo (centered) - only if we obtained hex string
                    if (imgHexString != null) {
                        // note: per README, <img> must be on its own line, and preceded by an alignment tag
                        append("[C]<img>$imgHexString</img>\n")
                    }
                    append("[C]==============================\n")
                    append("[C]$branchName\n")
                    append("[C]$branchAddress\n")
                    append("[C]Timezone: $branchTimezone\n")
                    append("[C]------------------------------\n")
                    append("[C]Receipt for Order ${orderData.getString("order_number")}\n")
                    append("[L]Date: ${orderData.getString("order_date")}\n")
                    append("[L]Served by: $deviceName\n")
                    append("[C]------------------------------\n")

                    // Items
                    val items = orderData.getJSONArray("items")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val name = item.getString("menu_item")
                        val qty = item.getInt("quantity")
                        val price = item.getDouble("price")
                        append("[L]$name  x$qty   [R]$branchCurrency ${"%.2f".format(price * qty)}\n")
                    }

                    append("[C]------------------------------\n")
                    append("[R]TOTAL: $branchCurrency ${"%.2f".format(orderData.getDouble("total"))}\n")
                    append("[C]==============================\n")
                    append("[C]<barcode type='39' height='64' width='2'>${orderData.getString("order_number")}</barcode>\n")
                    append("[C]Thank you for your purchase!\n")
                    append("[C]==============================\n\n\n")
                }

                // Print and cut
                p.printFormattedTextAndCut(formattedText)
            }
        } catch (e: IOException) {
            Log.e("Printer", "Print error: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e("Printer", "Unexpected print error: ${e.message}")
            throw e
        }
    }

    suspend fun disconnect() {
        try {
            printer?.disconnectPrinter()
            printer = null
        } catch (e: IOException) {
            Log.e("Printer", "Disconnect failed: ${e.message}")
        } catch (e: Exception) {
            Log.e("Printer", "Unexpected disconnect error: ${e.message}")
        }
    }

    suspend fun testConnection(): Boolean {
        return try {
            printer?.printFormattedTextAndCut("[C]Test Print\n")
            true
        } catch (e: IOException) {
            Log.e("Printer", "Test failed: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("Printer", "Unexpected test error: ${e.message}")
            false
        }
    }
}