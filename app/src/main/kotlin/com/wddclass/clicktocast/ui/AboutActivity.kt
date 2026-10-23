package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.widget.TextView
import com.wddclass.clicktocast.BuildConfig
import com.wddclass.clicktocast.R

class AboutActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<TextView>(R.id.version).text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        val back = findViewById<TextView>(R.id.back)
        Pills.attach(back)
        back.setOnClickListener { finish() }
        back.requestFocus()
    }
}
