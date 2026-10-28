# 通用调用点摘要接入实现与本机验证

以下保留第一轮实现与验证记录。当前验收数据、审查修复及 Linux 全量结果见 [FINAL-REVIEW.md](FINAL-REVIEW.md)。

2026-10-28。设计约束见 [ARCHITECTURE.md](ARCHITECTURE.md)。本轮只做本地提交，使用三个隔离工作树；公共分支及第一份方法展开权限策略保持原状。

## 为什么需要独立调用身份

一条 API 调用可以通过摘要调用另一个对象的方法。它们共享源语句，但被调声明、接收对象和实际目标不同。原型只向 PAG 添加 receiver/参数赋值，没有向按需分析登记对应的调用上下文。两次调用同一个回调类时，普通 SPARK 可以传播数据，按需分析却不能按回调对象区分参数：基线探针保留正例，同时混入另一个对象的数据。

本轮在局部摘要实际创建 PAG 赋值边时登记 `PAG.CallSiteModelCall`，保存源语句、调用方、回调声明、接收对象和实际赋值对。记录按源语句分组；当前协议只允许一个 DirectTarget，因此一次逻辑调用可对应多个动态实现，不会按某个具体实现拆成多个调用。回调声明使用摘要的声明类型和子签名解析，协变实现仍保持原合同声明。未显式给出类型且接收者是数组时，沿用 VirtualCalls 的数组到 Object 规则解析声明。

`ContextSensitiveInfo` 为这些记录分配独立 ID，与原 API 复用同一套参数边、返回边及实际目标登记流程。原 API 的 `callAssigns`、声明和既有 receiver 元数据保持原状。记录容器仅在 `cs-demand` 开启时创建；历史全局 GENERIC_FAKE 模型的处理路径保持原状。

## 如何进入实际构图生命周期

保留标准 SPARK 的求解和创建链：SparkTransformer → ContextInsensitiveBuilder → OnFlyCallGraph → OnFlyCallGraphBuilder。前两层的小型 protected 工厂使客户端能够覆盖已有 `processNewMethod()`，在原有范围守卫之后、首次处理可达方法时准备摘要，再委托原处理流程。预加载体、MethodSource 冷加载体和构图期间生成的体共用此入口。

引擎增加通用的 `CallSiteSummarySparkTransformer`，只接收方法准备函数。XML 会话提供该函数，并显式提供直接构图用 transformer 和正式 cg pack 的执行入口。关闭模式走原路径。开启模式临时替换 `cg.spark`，保留阶段声明、默认选项、实际选项和位置；成功和异常都恢复原 Transform 对象。未知客户端 transformer 会被明确拒绝。非 on-the-fly SPARK、VTA、RTA、Geom 模式会得到明确诊断。

内部 Soot 删除 Scene 中的旧 provider 字段、注册/调用方法以及旧调用点；保留 `LibraryBodyFilter`、MethodPAG 和 VirtualCalls 的原权限策略。引擎 XML 规则、模型端口与已有测试期望均保留。

## 固定源码与制品

| 组件 | 本地实现提交 | 制品 |
| --- | --- | --- |
| 上游 | `29c1801a4b17f0f724526e4b1b45d781acb8b04a` | `sootclasses-trunk.jar` |
| 内部 Soot | `ace121762ea5b0496d612aff4183318d9e9334e2` | `4.7.1-callback-general-20261028-SNAPSHOT` |
| 引擎 | `d5aa5fcfd192ee7f6f8fcc75de18d2b60b11ef88` | `CleanCode-1.4.3-beta.1.jar` |

内部构建 JAR 与本地 Maven 安装 JAR 的 SHA-256 相同：`da38aec3bcdb1c154d7d844d3b9e69abd62265a2fe103b255ecbb96a0ab3d97a`。引擎 JAR：`692550dcc5b28f9c4beda5c88d7ff6fb24dd30d7bb69c79e6ad024dbe8160f4c`。

每个修改文件的摘要、原始/脱敏日志摘要、报告和制品摘要保存在 `%TEMP%/stsa-soot-callback-general-20261028/implementation-evidence.json`；原始日志保留于该临时目录，脱敏版在 `redacted/`，引擎报告脱敏副本在 `engine-reports-redacted/`。生成脚本在隔离工作树的 `scratch/gather-implementation-evidence.py`。保留证据不依赖 Maven target 目录。

## 本机验证结果

使用 JDK 11.0.30，Maven 堆 768 MiB，测试 JVM 堆 1024 MiB，ActiveProcessorCount=4。上游独立序列以 clean 开始；最终 `upstream-final-verify3.log` 的 verify 通过，32 项测试、0 失败/错误/跳过，Checkstyle 0，license 检查通过。内部 `internal-final-install.log` 的 install 通过，39 项测试、0 失败/错误/跳过，Checkstyle 0，license 检查通过。

| 验收行 | 实际执行的覆盖 |
| --- | --- |
| 预加载、冷方法、后生成体 | 3 项嵌套回调测试；各方法只准备一次，receiver/参数传播并解析后续虚调用 |
| STATIC/VIRTUAL/INTERFACE/SPECIAL | 每种调用各 4 种按需形态，使用 Scene 实际 DemandCSPointsTo，默认 refinement 保持开启 |
| 两次回调的数据区分 | 每种调用均保留第一个 payload，排除另一个对象的 payload |
| 多态与协变 | 每个逻辑调用保留合同声明及多个实际回调目标；另测两种 API 多态，原 API 目标和元数据保持独立 |
| null 抑制、未标记语句 | 完整构图中仅选定语句丢弃默认摘要，普通 API 边保留，另一语句的默认回调和 receiver 传播保留 |
| 数组接收者 | array.clone() 的本地摘要与原 API 共用声明时仍有两个身份，模型 receiver 登记正确 |
| 实际 XML/OFF/路径/归属 | 92 项既有 XML 集成测试全部通过；直接 transformer 与正式 cg pack 均执行 |
| 阶段恢复及模式诊断 | 8 项适配器测试实际运行，覆盖成功、准备失败、未知 transformer、关闭 SPARK 及 4 种未支持模式 |

引擎从仓库根目录以 `mvn clean -Dmaven.test.skip=false -pl analyzer -am ... test` 启动独立序列。最终同一 clean 序列运行 14 类模型/solver/SM 回归并 package：`engine-final-regression-package.log` 共 272 项，0 失败/错误，3 个已有 SMCallsTest 跳过，269 项执行。XML 集成 92 项、适配器 8 项均未跳过。新测试已精确加入 Maven testIncludes 和 Surefire includes。

三个既有跳过分别为 `testReturnStatementC_callableReturn`、`testReturnStatementC_callableReturnTypeDefinition` 和 `testUnknownFunctionB_taintTransferFunction`，原因是既有 SM 能力边界；未计为本次验收覆盖。完整 Linux clean verify 的独立核验由主审另行记录。

## 失败样本及保留边界

- `upstream-red-bounded.log`：31 项运行、18 项失败，期望两个模型调用上下文而实际为零，先于生产修复保存。
- `upstream-array-red2.log`：数组接收者实际触发新代码的 ArrayType→RefType 强转异常，修复前证据保留。第一次数组夹具用不同 API 签名，受既有 CG 数组签名处理限制没有模型边；该夹具失败也保留。
- 首次无限堆 JVM 因 Windows 提交额度不足启动失败，日志和 JVM 错误文件保留；随后限制堆和线程数。编译、移植差异、Checkstyle 行长等中间失败日志均保留。
- `ordinary-api-receiver-baseline-success.log`：独立旧源码探针证明 VIRTUAL/INTERFACE 的两个不同 API 实现原本没有 receiver 标记。原普通路径未修复这个既有问题；正式测试核对其已有元数据原样保留，摘要的实际按需正负查询通过。

本次协议仍限于 on-the-fly 标准 SPARK、GENERIC_FAKE、单个 DirectTarget 和直接引用参数映射。方法进入分析后应保持稳定；任意后续方法体重写与摘要失效不在本轮范围。
