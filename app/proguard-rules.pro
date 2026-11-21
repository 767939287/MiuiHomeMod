-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
# 保留模块入口类名，避免 java_init.list 被改写成默认包的混淆名
-keep public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
