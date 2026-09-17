# FoldReader R8 规则
#
# Room / Compose / AndroidX 都自带 consumer rules，正常情况下这里不需要额外 keep。
# 保留本文件是为了给后续可能出现的反射/序列化路径留一个统一的落点。
#
# 注意：开启 R8 后必须实机验证 release 包（尤其是 Room 迁移、EPUB 解析、字体加载
# 这几条依赖「类名/成员不被改写」的路径）。
