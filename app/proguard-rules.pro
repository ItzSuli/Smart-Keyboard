# Smart Keyboard keeps no reflective entry points beyond what the manifest declares.
# The Anthropic SDK ships its own consumer rules (Jackson / Kotlin reflection).

# Compile-time-only annotation referenced by OpenTelemetry (a transitive SDK dependency).
-dontwarn com.google.auto.value.AutoValue

# LiteRT-LM (offline AI): its classes are called from native code and read with Gson /
# reflection, and the library ships no rules of its own.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
