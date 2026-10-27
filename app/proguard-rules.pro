# Smart Keyboard keeps no reflective entry points beyond what the manifest declares.
# The Anthropic SDK ships its own consumer rules (Jackson / Kotlin reflection).

# Compile-time-only annotation referenced by OpenTelemetry (a transitive SDK dependency).
-dontwarn com.google.auto.value.AutoValue
