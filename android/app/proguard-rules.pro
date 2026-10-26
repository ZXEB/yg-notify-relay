# 通知监听服务由系统按组件名绑定，keep 住监听入口与系统回调，避免任何裁剪/重命名意外。
-keep class dev.yg.relay.service.RelayNotificationService { *; }
-keep class dev.yg.relay.service.SmsRelayReceiver { *; }

# WorkManager 用反射实例化 Worker，必须保留构造签名。
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# 数据模型要过 kotlinx.serialization，保留序列化器与伴生对象。
-keepclassmembers class dev.yg.relay.model.** {
    *** Companion;
}
-keepclasseswithmembers class dev.yg.relay.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp 在 Android 上会自己判断平台能力，这些可选依赖的告警没有意义。
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
