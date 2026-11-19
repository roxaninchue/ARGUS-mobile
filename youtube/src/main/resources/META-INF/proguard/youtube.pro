# The app finds the YouTube plug-in by class name (StoreSources.plugin(StoreSources.YOUTUBE_CLASS): reflection on the
# Kotlin object's INSTANCE field), so R8 must keep the class and that field. Applied automatically from this jar.
-keep class com.argus.assistant.mediaapps.youtube.YouTubeSource { *; }
