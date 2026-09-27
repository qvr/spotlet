# The native core calls back into these by name over JNI (see rust/src/lib.rs:
# send_native_*). R8 can't see those call sites, so keep the class and its static
# callback methods, plus every native method on the bridge.
-keep class fi.qvr.spotlet.ReceiverService {
    public static void onNative*(...);
}
-keep class fi.qvr.spotlet.NativeBridge {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
