# release 构建的混淆规则。
#
# 原则：**只保留 R8 自己判断不出来的东西。** 库自带 consumer 规则的，这里不要再抄一份 ——
# 抄一份的代价是库升级后这边不会跟着变，而且改的时候不知道改哪个。
# 判断方法：解压依赖的 jar，看 `META-INF/proguard/` 里有没有同名规则。
#
# 规则按"会被混淆破坏的东西"分组。改任一条前先确认对应功能仍能在真机上跑通：
# 混淆问题在桌面上验不出来（见文件末尾）。


# --- 跨进程序列化契约 ------------------------------------------------------
# Shizuku UserService 的 AIDL 接口：App 端与服务端必须保持同名才能 transact。
# AIDL 生成的 Stub 不在任何库的 consumer 规则里，必须自己 keep。

-keep interface magicau.mtkcontroller.IRuntimeService { *; }
-keep class * implements magicau.mtkcontroller.IRuntimeService { *; }


# --- 枚举 ------------------------------------------------------------------
# valueOf(String) 按名字查枚举，名字被混淆就查不到。
# 实测有 6 处在用（门禁、方案切换、检查分类）。

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# --- 版本信息 --------------------------------------------------------------
# 诊断报告要带版本号与构建号，这两个字段混淆后必须仍可读。

-keep class magicau.mtkcontroller.BuildConfig { *; }


# --- kotlinx.serialization 的 json 子包 ------------------------------------
# **库自带规则里没有这两条。** kotlinx-serialization-core 的
# `META-INF/proguard/kotlinx-serialization-common.pro` 只覆盖 @Serializable 的
# 伴生对象与 serializer，没有覆盖 json 包本身，所以这里要自己写。

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}


# --- 注解与内部类 ----------------------------------------------------------
# **这条比库自带的宽。** 库保留的是 `RuntimeVisibleAnnotations, AnnotationDefault`，
# 而 `*Annotation*` 会保留所有注解属性；`InnerClasses` 也是库没要求的。
#
# 为什么不跟着收窄：这条同时支撑 kotlinx.serialization 的注解读取与 AIDL 的
# 异常传递，收窄需要真机逐条验证（方案保存/套用、备份导出导入、提权链路）。
# 已知它让出了一些优化空间，属于**用体积换确定性**，不是疏漏。
# 要收窄的话：先改成 `RuntimeVisibleAnnotations,AnnotationDefault` 跑一遍上面三条路径。

-keepattributes *Annotation*, InnerClasses


# --- 崩溃栈可读 ------------------------------------------------------------
# 保留行号与源文件名，混淆后仍能从 stack trace 定位到真实代码位置。
# 不上传 mapping 文件到任何外部服务。

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# ---------------------------------------------------------------------------
# 真机复验清单 —— 只在**改动了本文件**时执行。
#
# 桌面构建验不出混淆问题，所以上面每条规则改完都要在真机上跑一遍下面三条。
# 规则没改的构建不必重跑：这一节是一张"改动后要过哪些关"的清单，
# 不是一份待办。
#   1. 方案保存 / 套用        —— kotlinx.serialization
#   2. 配置导出 / 导入        —— 同上，另一条路径
#   3. Shizuku 授权 + 调频    —— AIDL 跨进程
# ---------------------------------------------------------------------------
