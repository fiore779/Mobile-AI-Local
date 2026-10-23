# JNI: native メソッドは名前(Java_com_mobileailocal_...)で C++ から結び付くので、クラス名もメソッド名も変えない
-keepclasseswithmembernames class * { native <methods>; }
# JNI の GetMethodID("accept", "(Ljava/lang/String;)Z") で名前を指定して呼ぶ
-keep interface com.mobileailocal.domain.TokenSink { *; }
