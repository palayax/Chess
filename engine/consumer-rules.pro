# R8 rules for consumers of :engine.
#
# libstockfish.so exports Java_net_palaya_chessanalyzer_engine_NativeBridge_native* (see
# src/main/cpp/jni_bridge.cpp), and the JVM binds those by the class's and the methods' exact names.
# Renaming either breaks every engine call with UnsatisfiedLinkError, so both are pinned here. The C++
# side never calls back into Java (no FindClass/GetMethodID/GetFieldID), so nothing else needs keeping.
-keep class net.palaya.chessanalyzer.engine.NativeBridge {
    native <methods>;
}
