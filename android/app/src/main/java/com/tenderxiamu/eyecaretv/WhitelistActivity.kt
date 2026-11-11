package com.tenderxiamu.eyecaretv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 手动白名单：勾选的应用处于前台时，到点不强制打断，
 * 等用户切出该应用后再补弹提醒。
 */
class WhitelistActivity : Activity() {

    private lateinit var store: ConfigStore
    private lateinit var listView: ListView
    private lateinit var textUsageState: TextView
    private lateinit var btnUsage: TextView
    private var entries: List<AppWhitelist.AppEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        setContentView(R.layout.activity_whitelist)

        listView = findViewById(R.id.list_apps)
        textUsageState = findViewById(R.id.text_usage_state)
        btnUsage = findViewById(R.id.btn_usage_access)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
        btnUsage.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { toast(getString(R.string.whitelist_usage_failed)) }
        }

        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshUsageState()
        syncChecks()
    }

    private fun loadApps() {
        entries = runCatching { AppWhitelist.launchers(this) }.getOrDefault(emptyList())
        if (entries.isEmpty()) {
            toast(getString(R.string.whitelist_empty))
        }
        val labels = entries.map { it.label }
        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            labels,
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as TextView).apply {
                    setTextColor(Color.parseColor("#F3F8F6"))
                    textSize = 15f
                    setPadding(8, 20, 8, 20)
                }
                return view
            }
        }
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val packageName = entries.getOrNull(position)?.packageName ?: return@setOnItemClickListener
            val current = store.whitelist.toMutableSet()
            if (listView.isItemChecked(position)) current.add(packageName) else current.remove(packageName)
            store.whitelist = current
        }
        syncChecks()
    }

    private fun syncChecks() {
        val chosen = store.whitelist
        entries.forEachIndexed { index, entry ->
            listView.setItemChecked(index, chosen.contains(entry.packageName))
        }
    }

    private fun refreshUsageState() {
        val granted = AppWhitelist.hasUsageAccess(this)
        textUsageState.text = getString(
            if (granted) R.string.whitelist_usage_granted else R.string.whitelist_usage_missing,
        )
        btnUsage.text = getString(if (granted) R.string.perm_granted else R.string.btn_grant)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
