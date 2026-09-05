package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        requestCameraPermission()

        val layout =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    40,
                    60,
                    40,
                    40
                )
            }

        val title =
            TextView(this).apply {
                text = "EyeNav"
                textSize = 32f
            }

        val description =
            TextView(this).apply {

                text =
                    "\nEye-controlled Android navigation\n\n" +
                    "Foundation build\n\n" +
                    "Next: eye tracking, calibration and gaze cursor."

                textSize = 17f
            }

        val button =
            Button(this).apply {

                text =
                    "Enable EyeNav Accessibility"

                setOnClickListener {

                    startActivity(
                        Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )
                }
            }

        layout.addView(title)
        layout.addView(description)
        layout.addView(button)

        setContentView(layout)
    }

    private fun requestCameraPermission() {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                100
            )
        }
    }
}
