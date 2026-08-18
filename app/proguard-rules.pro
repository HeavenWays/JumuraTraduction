# Règles ProGuard (release non minifié pour l'instant : rien de spécial requis).
# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class org.json.** { *; }
