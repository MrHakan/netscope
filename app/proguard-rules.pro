# JmDNS reflects over its own service info classes.
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Room and Hilt generate code that must survive shrinking.
-keep class com.netscope.core.database.** { *; }

# Kotlin coroutines internals referenced reflectively by the debugger agent.
-dontwarn kotlinx.coroutines.**
