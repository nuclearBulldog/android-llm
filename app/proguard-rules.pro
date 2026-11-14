# Preserve LiteRT-LM JNI entry points and model reflection targets if R8 is enabled.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
