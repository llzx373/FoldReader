# 安全策略

## 支持范围

只对[最新 Release](https://github.com/llzx373/FoldReader/releases/latest) 提供安全修复。预发布阶段不维护多个版本分支。

## 报告漏洞

请**不要**通过公开 issue、讨论区或社交渠道披露安全漏洞。

使用 GitHub 的私密漏洞报告：进入仓库的 **Security → Report a vulnerability**（[直接链接](https://github.com/llzx373/FoldReader/security/advisories/new)）。请在报告里写清：

- 受影响的版本
- 复现步骤或最小化的恶意样本文件
- 影响范围（崩溃 / 内存耗尽 / 读取越界 / 数据破坏等）与你的判断依据

会在确认后尽快回应，并在修复发布后再公开细节。

## 威胁模型

FoldReader 是一个纯本地应用，这决定了它的攻击面比常见的联网应用小得多：

- **无网络访问**。`AndroidManifest.xml` 中没有声明 `INTERNET` 权限，也没有其他任何权限。应用无法外发数据。
- **无账号、无服务端**。不存在凭据、会话或服务端接口层面的问题。
- **只通过 GitHub Releases 分发**。APK 用正式 keystore 签名，签名校验可以通过 `apksigner verify --print-certs` 自查。

因此主要攻击面是**用户主动打开的文件**：

| 入口 | 涉及组件 |
| --- | --- |
| 漫画容器（zip / tar / 7z / rar） | junrar、Apache Commons Compress、XZ for Java |
| PDF | androidx.pdf（渲染位于沙箱进程）、PDFBox-Android |
| EPUB / FB2 | 自研 XML / HTML 解析与拍平逻辑 |
| 纯文本 | 编码检测与窗口化读取 |

对这条链路上的问题尤其关心：归档解压时的路径穿越（zip-slip）、压缩炸弹导致的解压膨胀、畸形 XML/HTML 触发的解析崩溃或越界、图片解码的资源耗尽。若能在**不依赖用户点击**的前提下触发（例如导入后自动预热阶段），请标注为高优先级。

## 不受理的情形

- 需要 root、已解锁 bootloader 或物理接触设备的攻击
- 需要用户手动安装恶意应用、授予高风险权限的攻击
- 第三方依赖中尚未被本项目使用的代码路径
- 单纯的功能缺失或体验问题（请走普通 issue）
