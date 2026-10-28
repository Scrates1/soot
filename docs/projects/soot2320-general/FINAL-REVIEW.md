# 第二份 Soot PR：通用摘要接入的最终验证

2026-10-28。本轮只在隔离分支实施和本地提交，尚未推送或对外回复。第一轮记录见 [IMPLEMENTATION.md](IMPLEMENTATION.md)，机制与边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。本记录用于替代第一轮数据作为当前验收依据，不涉及扫描耗时优化。

## 最终设计

客户端在已有 `OnFlyCallGraphBuilder.processNewMethod()` 首次可达入口准备摘要。两个 protected 创建方法只负责连接标准 SPARK 创建链，不引入第二套方法处理回调。引擎显式选择自己的 SparkTransformer；Soot 核心只消费调用点上的通用摘要，不保存全局客户端 provider。

原 API 与摘要回调共享源语句，各自保留独立调用身份。PAG 保存回调声明、receiver 和实际参数赋值，按需分析使用独立上下文，支持同一逻辑调用的多个动态实现及协变返回。普通 API 边、原返回值、类归属和第一份权限策略保持各自原有职责。

本轮审查修复了以下问题：

- 数组 receiver 必须按回调子签名解析，不能回退到原 API 签名。新增测试使用 `scheduleArray(array) → array.clone()`，避免源与目标同名掩盖缺陷。
- 显式声明的回调类型约束动态分派，排除只有相同签名的无关类。
- `ExpectedTypeTag` 表达的已知返回分配与回调效果可以同时存在。普通 API 返回值保留，回调返回值不自动流入原 API 返回值。
- 回调 receiver 目前支持 Local；null 常量没有执行目标。其他非 Local receiver 显式报错。直接参数映射仍支持合法引用常量，不构造伪 Jimple。
- 临时 `cg.spark` 阶段恢复后，从 PhaseOptions 移除临时对象的显式选项，释放对象及客户端闭包。新增的 `clearPhaseOptions` 只处理配置生命周期，默认选项仍有效。
- RadioScenePack 插入已启用阶段会重置兄弟阶段的 enabled。接入前检查互斥配置是否合法，成功或异常后恢复兄弟阶段的原值；未知自定义 transformer 继续明确拒绝。

范围仍为标准 on-the-fly SPARK、GENERIC_FAKE、单个 DirectTarget、直接引用参数映射。VTA、RTA、Geom 和关闭 on-the-fly 的模式显式拒绝。本轮未修改历史全局摘要行为，也未修复独立存在的普通 API receiver 元数据旧问题。

## 源码与制品绑定

| 组件 | 本地修复提交 |
| --- | --- |
| 上游 Soot | `bbcb82fb510e5d20c9369b1b6a7db2e19d009062` |
| 内部 Soot | `e822e3925b678477d3362843a2aafda5c6e171c6` |
| 引擎 | `23032192196e164f8d4a40e877921cd6f7b6a6e7` |

Linux 使用上游提交的 `git archive`，tree 为 `f69c681feef18dcb63b1a997fde85af022fd94ef`；归档 SHA-256 为 `13187448b28328e43d45d9c097a77c7ef2bb95724bb375686b368b98e0d9393d`。后续文档提交不改变已验证的生产和测试源码。

内部依赖仍为独立本地版本 `4.7.1-callback-general-20261028-SNAPSHOT`。最终引擎 JAR SHA-256 为 `027ef0bda5b0ad71e8508be132500d2aaff886da568e79fde79bf274d692823f`。独立核对了 fat JAR 中 9 个关键 Soot class 与已安装依赖的字节摘要，一致；实际包内适配器能够加载和实例化，旧 provider API 不存在。

## 测试结果

使用 JDK 11.0.30；本机 Maven 和测试 JVM 均限制堆与处理器数。引擎独立序列从根目录 clean 开始，后续增量验证沿用同一序列。报告中的总数包含单列的已有跳过。

| 验证 | 总数 | 失败 / 错误 | 已有跳过 | 结果 |
| --- | ---: | ---: | ---: | --- |
| 上游指定测试及 verify | 40 | 0 / 0 | 0 | 格式、版权检查通过 |
| 内部 Soot 指定测试及 install | 47 | 0 / 0 | 0 | 格式、版权检查通过 |
| 实际 XML 与适配器 | 102 | 0 / 0 | 0 | XML 93 项，适配器 9 项 |
| 引擎 14 类回归及 package | 274 | 0 / 0 | 3 | BUILD SUCCESS，271 项执行 |
| Linux clean verify -PJava11 | 504 | 0 / 0 | 9 | BUILD SUCCESS，495 项执行 |

Linux 完整验证耗时 423.989 秒，仅表示本轮测试运行时间，不作为分析器扫描性能数据。9 项跳过与前轮完全一致，主要属于已有 Sils、冗余语句及异常分析测试；引擎 3 项仍为已有 SMCallsTest 能力边界，未增加跳过或放宽断言。

除原有生命周期、STATIC/VIRTUAL/INTERFACE/SPECIAL、多态、协变与按需矩阵外，新增覆盖了不同签名的数组回调、声明类型限制、返回标签共存、引用返回隔离、源 base 参数映射、引用常量参数和不支持的映射诊断。新增实际 XML 测试通过正式 cg pack 开启 cs-demand，并保持 refinement：第一个回调对象的数据保留，第二个对象的数据被排除。

适配器测试通过实际 pack 验证成功、准备异常和全部不支持模式的恢复。独立使用最终 fat JAR 连续构图三次，每次移除临时阶段后保留的客户端阶段数均为零。

## 失败样本与封存

新增测试先在未修复代码运行：上游 37 项中 4 项失败，分别复现数组漏边、无关目标、返回标签覆盖及常量 receiver 静默丢失；原有 32 项仍通过。适配器 9 项中 7 项失败，复现兄弟阶段配置与异常恢复问题。失败日志及报告均保留，不计入通过结果。

中间运行还保留了指定测试未匹配到另一个 Surefire 阶段、格式行长、引用常量未开启精确字符串选项，以及一次在回迁前执行的内部基线构建。它们均单独标识，未作为最终修复验证。

证据根目录为 `%TEMP%/stsa-soot-callback-general-20261028/`。第一轮证据未覆盖；当前日志使用 `reviewfix-` 前缀，Linux 报告位于 `reviewfix-linux/`，制品独立检查位于 `reviewfix-package/`，最终不可覆盖的摘要台账及脱敏副本位于 `reviewfix-sealed/`。台账保存源码提交、文件摘要、原始/脱敏报告摘要、制品摘要和已有跳过身份；保留证据不依赖 Maven target。
