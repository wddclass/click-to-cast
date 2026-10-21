package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.prefs.CastRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : TvActivity() {
    private val connection = CastConnection(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        val clear = findViewById<TextView>(R.id.clear)
        Pills.attach(clear)
        clear.setOnClickListener {
            connection.api?.clearHistory()
            show(emptyList())
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bind { api -> show(api.history()) }
    }

    override fun onStop() {
        connection.unbind()
        super.onStop()
    }

    private fun show(items: List<CastRecord>) {
        val list = findViewById<ListView>(R.id.list)
        val empty = findViewById<TextView>(R.id.empty)
        val clear = findViewById<TextView>(R.id.clear)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        clear.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        list.adapter = HistoryAdapter(items)
        if (items.isNotEmpty()) list.requestFocus() else clear.requestFocus()
    }

    private class HistoryAdapter(private val items: List<CastRecord>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): CastRecord = items[position]
        override fun getItemId(position: Int): Long = items[position].session

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            val item = items[position]
            view.findViewById<TextView>(R.id.row_title).text = item.title
            val quality = if (item.quality.isBlank()) "" else " · ${item.quality}"
            val whenText = CLOCK.format(Date(item.at))
            view.findViewById<TextView>(R.id.row_meta).text =
                "${item.source}$quality · ${item.outcome} · $whenText"
            return view
        }

        companion object {
            private val CLOCK = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        }
    }
}
