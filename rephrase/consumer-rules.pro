# R8 rules for consumers of :rephrase (C2).
#
# librephrase.so exports Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_native* (src/main/cpp/jni_bridge.cpp),
# and the JVM binds those by the class's and the methods' exact names. Renaming either breaks every call with
# UnsatisfiedLinkError, so both are pinned here. The C++ side never calls back into Java (no FindClass, GetMethodID
# or GetFieldID), so nothing else needs keeping.
-keep class net.palaya.chessanalyzer.rephrase.NativeRephrase {
    native <methods>;
}
