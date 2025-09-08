package com.carousel.agent

data class PrinterConfig(
    val printerId: String,
    val connectionType: String,
    val vendorId: String? = null,
    val productId: String? = null,
    val ipAddress: String? = null,
    val serialPort: String? = null,
    val profile: String = "TM-T88III",
    val isDefault: Boolean = false,
    val fingerprint: String? = null,
)
