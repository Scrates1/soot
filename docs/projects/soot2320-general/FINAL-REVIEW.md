# 第二份 Soot PR：通用摘要接入的最终验证

2026-10-28。本轮只在隔离分支实施和本地提交，尚未推送或对外回复。第一轮记录见 [IMPLEMENTATION.md](IMPLEMENTATION.md)，机制与边界见 [ARCHITECTURE.md](ARCHITECTURE.md)。本记录用于替代第一轮数据作为当前验收依据，不涉及扫描耗时优化。

## 最终设计

客户端在已有 `OnFlyCallGraphBuilder.processNewMethod()` 首次可达入口准备摘要。两个 protected 创建方法只负责连接标准 SPARK 创建链，不引入第二套方法处理回调。引擎显式选择自己的 SparkTransformer；Soot 核心只消费调用点上的通用摘要，不保存全局客户端 provider。

原 API 与摘要回调共享源语句，各自保留独立调用身份。PAG 保存回调声明、receiver 和实际参数赋值，按需分析使用独立上下文，支持同一逻辑调用的多个动态实现及协变返回。普通 API 边、原返回值、类归属和第一份权限策略保持各自原有职责。

本轮审查修复了以下问题：

- 数组 receiver 必须按回调子签名解析，不能回退到原 API 签名。新增测试使用 `scheduleArray(array) → array.clone()`，避免源与目标同名掩盖缺陷。
- 显式声明的回调类型约束动态分派，排除只有相同签名的无关类。
- 当子类继承父类实现时，在 PAG 复用 typed-local/cast-node 机制，先限制 receiver 再传入父类 this，并将同一个受限 receiver 用于按需身份。只有原 receiver 类型不能保证摘要类型时才增加节点。
- 对 AnySubType 未知对象，通过已有 VirtualCalls 双类型约束入口计算源 bound 与摘要 bound 的交集；构图与按需细化共用同一解析逻辑。抽象类中的非抽象实现仍保留，按需结果与已有调用目标求交。
- 按需细化的反向遍历可能越过类型收窄；结果 allocation 保持在本次查询根节点已求解 PAG 集合的上界内，同时保存 context。独立普通 cast 测试也先复现该行为再验证修复，未关闭 refinement。
- `ExpectedTypeTag` 表达的已知返回分配与回调效果可以同时存在。普通 API 返回值保留，回调返回值不自动流入原 API 返回值。
- 回调 receiver 目前支持 Local；null 常量没有执行目标。其他非 Local receiver 显式报错。直接参数映射仍支持合法引用常量，不构造伪 Jimple。
- 临时 `cg.spark` 阶段恢复后，从 PhaseOptions 移除临时对象的显式选项，释放对象及客户端闭包。新增的 `clearPhaseOptions` 只处理配置生命周期，默认选项仍有效。
- RadioScenePack 插入已启用阶段会重置兄弟阶段的 enabled。接入前检查互斥配置是否合法，成功或异常后恢复兄弟阶段的原值；未知自定义 transformer 继续明确拒绝。

范围仍为标准 on-the-fly SPARK、GENERIC_FAKE、单个 DirectTarget、直接引用参数映射。VTA、RTA、Geom、关闭 on-the-fly、merge/none 传播器和关闭 cg pack 的配置显式拒绝。本轮未修改历史全局摘要行为，也未修复独立存在的普通 API receiver 元数据旧问题。

## 源码与制品绑定

| 组件 | 本地修复提交 |
| --- | --- |
| 上游 Soot | `b62bca350e03186a60b9bda75675851444cdd756` |
| 内部 Soot | `ea6e1bada4735ecb304906f71acbc029bb816165` |
| 引擎 | `3bb6435524a7b575384e3ae5ced16498ce8193ab` |

Linux 使用上游提交的 `git archive`，tree 为 `d0ef2cf579d58443dd59393e6a5dbeccee7979e6`；归档 SHA-256 为 `b751fccd61d1b47334f6a38810a076ed5645fde6754f04fc6d8968efd72852d9`。后续文档提交不改变已验证的生产和测试源码。

内部依赖仍为独立本地版本 `4.7.1-callback-general-20261028-SNAPSHOT`。最终引擎 JAR SHA-256 为 `0315853f2d3e639864fb143a73c56b6ea8753e2a4f0816705d35db84ad614c9c`。独立核对了 fat JAR 中 11 个关键 Soot class 与已安装依赖的字节摘要，一致；实际包内适配器能够加载和实例化，旧 provider API 不存在。

## 测试结果

使用 JDK 11.0.30；本机 Maven 和测试 JVM 均限制堆与处理器数。引擎独立序列从根目录 clean 开始，后续增量验证沿用同一序列。报告中的总数包含单列的已有跳过。

| 验证 | 总数 | 失败 / 错误 | 已有跳过 | 结果 |
| --- | ---: | ---: | ---: | --- |
| 上游指定测试及 verify | 48 | 0 / 0 | 0 | 格式、版权检查通过 |
| 内部 Soot 指定测试及 install | 55 | 0 / 0 | 0 | 格式、版权检查通过 |
| 实际 XML 与适配器 | 105 | 0 / 0 | 0 | XML 93 项，适配器 12 项 |
| 引擎 14 类回归及 package | 277 | 0 / 0 | 3 | BUILD SUCCESS，274 项执行 |
| Linux clean verify -PJava11 | 512 | 0 / 0 | 9 | BUILD SUCCESS，503 项执行 |

Linux 完整验证耗时 362.644 秒，仅表示本轮测试运行时间，不作为分析器扫描性能数据。9 项跳过与前轮完全一致，主要属于已有 Sils、冗余语句及异常分析测试；引擎 3 项仍为已有 SMCallsTest 能力边界，未增加跳过或放宽断言。

除原有生命周期、STATIC/VIRTUAL/INTERFACE/SPECIAL、多态、协变与按需矩阵外，新增覆盖了不同签名的数组回调、声明类型限制、返回标签共存、引用返回隔离、源 base 参数映射、引用常量参数和不支持的映射诊断。新增实际 XML 测试通过正式 cg pack 开启 cs-demand，并保持 refinement：第一个回调对象的数据保留，第二个对象的数据被排除。

新增回归还覆盖普通与按需模式的继承 receiver 排除、两个接口的未知类型交集、未知对象按需查询正例、抽象接收类保留具体实现、null receiver，以及 ExpectedTypeTag 与两次回调参数映射共同作用时的数据隔离。

适配器测试通过实际 pack 验证成功、准备异常和全部不支持模式的恢复。独立使用最终 fat JAR 连续构图三次，每次移除临时阶段后保留的客户端阶段数均为零。

## 失败样本与封存

后续审查的失败样本也独立保留：继承 receiver 在普通与按需模式下污染、未知对象漏掉类型交集、普通 cast 的细化结果越过 PAG 上界、关闭父 pack/不支持传播器静默缺失，以及抽象接收类和两接口按需正例漏边。最终修复使用统一解析和类型约束，所有对应测试转为通过。

新增测试先在未修复代码运行：上游 37 项中 4 项失败，分别复现数组漏边、无关目标、返回标签覆盖及常量 receiver 静默丢失；原有 32 项仍通过。适配器 9 项中 7 项失败，复现兄弟阶段配置与异常恢复问题。失败日志及报告均保留，不计入通过结果。

中间运行还保留了指定测试未匹配到另一个 Surefire 阶段、格式行长、引用常量未开启精确字符串选项，以及一次在回迁前执行的内部基线构建。它们均单独标识，未作为最终修复验证。

证据根目录为 `%TEMP%/stsa-soot-callback-general-20261028/`。第一轮证据未覆盖；当前日志使用 `reviewfix-` 前缀，最终 Linux 报告位于 `reviewfix3-linux/`，制品独立检查位于 `reviewfix3-package/`，最终不可覆盖的摘要台账及脱敏副本位于 `reviewfix-sealed/`。台账保存源码提交、文件摘要、原始/脱敏报告摘要、制品摘要和已有跳过身份；保留证据不依赖 Maven target。

## 2026-10-29 代码审查跟进

代码审查新增并修复两类边界：`AnySubType` 抽象基类实现了回调接口、但场景中没有已知具体子类时，PAG 原有赋值检查会先丢弃其 points-to 边；现保留基类已满足接收类型的赋值，并在有界动态分派前处理该未知类型。普通 SPARK 与 cs-demand 均增加了回归覆盖。调用点摘要同时校验 receiver/source/target 索引和静态调用的 base 用法，避免无效映射延迟崩溃或静默丢失参数。新增构图测试确认非空局部摘要会覆盖冲突的全局默认摘要及其参数映射。

验证使用 JDK 11.0.30：

- `CallSiteVirtualEdgesTest` 54 项及 `PhaseOptionsTest` 1 项通过。
- `mvn -q -PJava11 surefire:test@default-test` 完整默认测试执行通过。
- `mvn -q clean -Dmaven.test.skip=false -PJava11 verify` 在 Windows 系统测试 `Shimple1Test.testStdLib` 处无法完成：测试将 `jrt:/` 传给 `Paths.get`，触发 `InvalidPathException`。系统测试阶段报告 97 项、0 失败、1 错误、1 跳过；这是 Windows 路径处理限制，默认测试执行单独通过。
