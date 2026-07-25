package app.duck.notes

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Application entry point. Boots the Hilt component graph at process start. */
@HiltAndroidApp
class DuckApp : Application()
