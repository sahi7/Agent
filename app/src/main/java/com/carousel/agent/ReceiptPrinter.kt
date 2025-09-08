package com.carousel.agent

import android.content.Context
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import android.util.Log
import android.hardware.usb.UsbManager
import org.json.JSONObject
import java.io.IOException

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

    suspend fun printReceipt(orderData: JSONObject) {
        try {
            printer?.let {
                val formattedText = buildString {
                    append("[C]************************\n")
                    append("[C]Receipt for Order ${orderData.getString("order_number")}\n")
                    append("[L]Date: ${orderData.getString("order_date")}\n")
                    append("[L]Branch: ${orderData.getString("branch_name")}\n")
                    append("[C]--------------------\n")
                    val items = orderData.getJSONArray("items")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        append("[L]${item.getString("menu_item")}: ${item.getInt("quantity")} x $${item.getDouble("price")}\n")
                    }
                    append("[C]--------------------\n")
                    append("[R]Total: $${orderData.getDouble("total")}\n")
                    append("[C]<barcode type='39' height='64' width='2'>${orderData.getString("order_number")}</barcode>\n")
                    append("[C]************************\n")
                }
                it.printFormattedTextAndCut(formattedText)
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