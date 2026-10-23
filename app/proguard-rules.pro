# Media3 ships its own consumer rules. Keep line numbers for playback failures on TVs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Foreground service and boot receiver are manifest entries; don't warn on optional TV APIs.
-dontwarn org.xmlpull.v1.**

# AirPlay pairing uses Ed25519, X25519 and AES-CTR from BouncyCastle. R8 must keep the tables.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.util.** { *; }
