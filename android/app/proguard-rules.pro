# Minification is disabled for this MVP (see app/build.gradle), so this file is
# intentionally empty apart from the rules we already know will be needed once it is
# switched on.

# Keep the ride-event model shape intact for Jackson/Moshi-style reflection later.
-keep class com.cabeye.rider.net.** { *; }

# OkHttp ships its own consumer rules; these silence the known platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
