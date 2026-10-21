# Media3 ships its own consumer rules. Keep line numbers for playback failures on TVs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Foreground service and boot receiver are manifest entries; don't warn on optional TV APIs.
-dontwarn org.xmlpull.v1.**
