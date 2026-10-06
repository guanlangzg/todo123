# UniFFI's JNA bindings reference the native library reflectively, and Room/KSP generate code that
# must survive shrinking in a release build.
-keep class app.arttodo.core.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
