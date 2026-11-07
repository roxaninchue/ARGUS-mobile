package com.blazeinchue.argus.movies

import android.app.Activity
import android.os.Bundle

/**
 * 0.46.2 one-tap add-source links (argus://add-source?url=… and https://blazeinchue.pages.dev/add-source?url=…). Reads only the
 * url parameter ([AddSourceLink.parse]); Argus Video's Movies tab then shows the source URL, how many providers it has, the
 * one-time notice, and a single Install button. Nothing is installed without that tap.
 */
class AddSourceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AddSourceLink.parse(intent?.dataString)?.let { url ->
            Movies.init(this)
            startActivity(android.content.Intent(this, com.blazeinchue.argus.mediaapps.VideoActivity::class.java)
                .putExtra(com.blazeinchue.argus.mediaapps.VideoActivity.EXTRA_ADD_SOURCE, url)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        finish()
    }
}
