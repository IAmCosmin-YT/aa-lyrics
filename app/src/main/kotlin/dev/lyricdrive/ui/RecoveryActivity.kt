package dev.lyricdrive.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.lyricdrive.CrashReporter
import dev.lyricdrive.R

/** Separate process, platform widgets, software rendering; never touches AppGraph or Compose. */
class RecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashReporter.report(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        content.addView(TextView(this).apply { setText(R.string.recovery_title); textSize = 24f })
        content.addView(TextView(this).apply { setText(R.string.recovery_body) })
        content.addView(Button(this).apply {
            setText(R.string.diagnostics_copy)
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LyricDrive crash", report))
            }
        })
        content.addView(Button(this).apply {
            setText(R.string.recovery_share)
            setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }, getString(R.string.recovery_share)))
            }
        })
        content.addView(Button(this).apply {
            setText(R.string.recovery_safe)
            setOnClickListener {
                startActivity(Intent(this@RecoveryActivity, MainActivity::class.java)
                    .putExtra(EXTRA_SAFE_GRAPHICS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        })
        content.addView(TextView(this).apply { text = report; setTextIsSelectable(true) })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    companion object {
        const val EXTRA_SAFE_GRAPHICS = "dev.lyricdrive.SAFE_GRAPHICS"
    }
}
