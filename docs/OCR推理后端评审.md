# OCR 推理执行后端（Execution Provider）评审（M35）

> 2026-10-09。对象：onnxruntime-mobile 1.18.0（M21 钉版，评审预算 ~10MB 见 docs/AI功能需求与实施.md §2.4）。
> 触发点：整卷漫画翻译里 OCR（RT-DETR 气泡检测 + 页级 det/rec）在 CPU 上是主要耗时。

## 可选后端盘点

| 后端 | 包 | 体积代价 | 结论 |
| --- | --- | --- | --- |
| CPU | onnxruntime-mobile 内置 | 0 | 现状，基线 |
| NNAPI | onnxruntime-mobile **内置** | 0 | 本次接入为可选开关（默认关） |
| XNNPACK | Java API 有 `addXnnpack`，mobile 精简包未编译该 EP | — | 不可用，排除 |
| GPU（GPU EP） | 需换 `onnxruntime-android` 全量包（1.30+ 约 53MB） | +46MB | 与 M21「钉 mobile 线」决策冲突，排除 |

验证方式（本机字节码/原生符号核实，非文档转述）：

- `javap ai.onnxruntime.OrtSession$SessionOptions`：`addNnapi()` / `addNnapi(EnumSet<NNAPIFlags>)` 存在；
- arm64-v8a `libonnxruntime.so` 内含 NNAPI 符号（mobile 包编译了 NNAPI EP）；
- `onnxruntime-android` 全量包体积数据见 libs.versions.toml 的 onnxruntime 条目注释（53MB）。

## 决策

1. **继续钉 onnxruntime-mobile 1.18.0**，不换 android 全量线——GPU EP 的体积代价（约 6 倍于 mobile 包）换不到确定的收益（GPU EP 对 det/rec 这类小卷积模型的算子覆盖有限，大量算子仍回落 CPU，还要搭一次换线评审）。
2. **NNAPI 作为可选设置项接入**（设置页「OCR 硬件加速（NNAPI）」，默认关）：
   - 默认关的理由：NNAPI 驱动质量因设备/芯片而异（部分设备上比 CPU 还慢，个别老驱动有精度问题）；ORT 对 NNAPI 不支持的算子自动拆回 CPU，但分区边界上的张量搬运本身有开销，收益必须实测确认；
   - 实现：det/rec/气泡检测/inpaint 全部会话经统一 `createSession`——开关开则 `SessionOptions.addNnapi()`，注册失败（无驱动/老系统）记诊断日志回落纯 CPU，功能永远可用；
   - 开关切换：容器监听设置流，变化即经各自互斥锁作废现有会话，下次使用按新开关重建（不拆在跑的推理）。
3. **不引入 XNNPACK 自定义构建**：自行编译 onnxruntime 带 XNNPACK 会把「钉官方分发、SHA-256 可校验」的供应链口径打破，不值。

## 真机待验

- 同一条整卷漫画翻译任务，NNAPI 开/关的端到端耗时对比（重点：RT-DETR 检测与 rec 逐行识别各占比）；
- NNAPI 开时识别结果与 CPU 的一致性（抽查若干页文本逐字比对）；
- 低电/发热下 NNAPI 是否被系统降级（表现应等同回落 CPU，不报错）；
- 无 NNAPI 驱动的设备（老机型/模拟器）开关开启时的回落路径是否安静（诊断日志应有一行「NNAPI 不可用，回落 CPU」）。
