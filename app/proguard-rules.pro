# MediaPipe and its protobuf configs are reached from native code and by reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn autovalue.shaded.**
-dontwarn javax.lang.model.**
-dontwarn org.checkerframework.**

# Readable crash reports: keep real class/method names and line numbers (the code is open
# source, so there's nothing to hide). Unused code is still removed.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
