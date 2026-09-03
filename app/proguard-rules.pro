# Nothing reflective in this app; the defaults are enough.
-dontwarn kotlinx.coroutines.**

# Ready for when isMinifyEnabled is turned back on.
# OplusHaptics reaches the vendor engine reflectively. These are platform classes, not app
# classes, so R8 cannot rename them — but keeping the call sites intact avoids any chance of
# the reflection helpers being inlined into something unrecognisable.
-keep class io.github.zalexanninev15.magicmusicv.haptics.OplusHaptics { *; }
-keep class io.github.zalexanninev15.magicmusicv.haptics.MagicFeedback { *; }
-keep class io.github.zalexanninev15.magicmusicv.haptics.MmvVoicing { *; }

# Settings and profiles are (de)serialised by name through org.json.
-keep class io.github.zalexanninev15.magicmusicv.settings.** { *; }
-keepclassmembers enum io.github.zalexanninev15.magicmusicv.** { *; }
