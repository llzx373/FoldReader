# FoldReader R8 规则
#
# Room / Compose / AndroidX 都自带 consumer rules，正常情况下这里不需要额外 keep。
# 保留本文件是为了给后续可能出现的反射/序列化路径留一个统一的落点。
#
# 注意：开启 R8 后必须实机验证 release 包（尤其是 Room 迁移、EPUB 解析、字体加载
# 这几条依赖「类名/成员不被改写」的路径）。

# commons-compress 把若干可选的压缩后端声明为「用到才加载」，APK 里自然不存在：
# zstd（com.github.luben:zstd-jni）、xz 之外的 LZMA 变体等。我们只用到 zip/tar/7z 的
# Deflate/LZMA/LZMA2/BZIP2 分支（LZMA 由 org.tukaani:xz 提供），缺这些类不影响运行。
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.junrar.**
# junrar 依赖 slf4j-api，但项目里没有绑定实现（Android 上会退化为 NOP logger）
-dontwarn org.slf4j.**

# PdfBox 的 JPXFilter（PDF 内嵌 JPEG2000 图像）依赖可选的 com.gemalto.jp2，
# 这个包没有随 pdfbox-android 一起提供，只在"页面上有 JP2 图"时才用得到。
# 我们渲染 PDF 页面走的是 androidx.pdf（平台渲染器），PdfBox 只用来抽文本/元数据/目录
# 与渲染封面——正文抽取完全不碰图像解码，所以缺它不影响主线。
# 代价说清楚：万一样张首页恰好是 JPEG2000，封面会渲染失败（回退成标题占位封面），
# 这只影响封面观感，不影响能不能读。
-dontwarn com.gemalto.jp2.**

# 关于「把仪器化测试跑到 release 包上」这条路（曾尝试用 testBuildType=release 验证
# R8 产物）：被测包一旦开 minify，androidTest APK 也会被 R8 压缩，而 AGP 的
# src/androidTestRelease/keepRules/*.keep 在这个版本里并不生效，测试脚手架会被一轮轮裁掉
# （androidx.tracing.Trace → kotlin.jvm.internal.Lambda → kotlin.LazyKt …）。
# 在应用规则里 -keep kotlin.** 能压住，但那是拿发布包的体积去补贴测试脚手架，不划算。
#
# 所以 release 路径的保证改成两条：① release 构建 + 资源压缩必须绿；
# ② 需要核对时看 R8 的删除清单（build/outputs/mapping/release/usage.txt）里有没有
#    沙箱服务必需的类——沙箱服务一旦缺类就是独立进程 FATAL、整个文档作废，
#    且现场只看到 DeadObjectException，很难倒推。注意不要用"dex 里搜类名字符串"
#    的办法核对：release 会混淆，搜不到不代表类不在。
