package com.carousel.agent

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.widget.Button
import android.widget.TextView

class PrinterAdapter(
    private val printers: List<PrinterConfig>,
    private val onRemove: (PrinterConfig) -> Unit,
    private val onChoose: (PrinterConfig) -> Unit,
    private val onTest: (PrinterConfig) -> Unit
) : RecyclerView.Adapter<PrinterAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.printer_name)  // Assume in item layout
        val removeButton: Button = view.findViewById(R.id.remove_button)
        val chooseButton: Button = view.findViewById(R.id.choose_button)
        val testButton: Button = view.findViewById(R.id.test_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_printer, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = printers[position]
        holder.nameText.text = "Printer: ${config.connectionType} - ${config.ipAddress ?: config.vendorId}"
        holder.removeButton.setOnClickListener { onRemove(config) }
        holder.chooseButton.setOnClickListener { onChoose(config) }
        holder.testButton.setOnClickListener { onTest(config) }
    }

    override fun getItemCount() = printers.size
}