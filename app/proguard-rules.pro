-dontwarn org.jetbrains.annotations.**

# 这些枚举的常量名会被 name() 写进 SharedPreferences，再用 valueOf() 读回来。
# 一旦 R8 重命名了常量，升级后新旧名字对不上，用户的通道/保活策略/日志等级
# 就会静默回退到默认值（读取处有 runCatching 兜底，所以不会崩，只会莫名其妙被重置）。
-keepclassmembers enum com.esurfing.client.core.Channel { <fields>; }
-keepclassmembers enum com.esurfing.client.core.PowerMode { <fields>; }
-keepclassmembers enum com.esurfing.client.core.LogLevel { <fields>; }

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# xz-java 里有一些只在 XZ/LZMA2 路径上用到的可选类, 我们只用裸 LZMA1 解码,
# R8 剪掉未引用的部分是对的, 但会为缺失的引用报警。
-dontwarn org.tukaani.xz.**
