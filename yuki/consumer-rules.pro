-keep class com.ditzzy.dsunext.yuki.NativeBridge {
    native <methods>;
}
-keep interface com.ditzzy.dsunext.yuki.YukiLogger {
    void onLog(java.lang.String);
}
-keep class com.ditzzy.dsunext.yuki.YukiException {
    <init>(java.lang.String);
}
