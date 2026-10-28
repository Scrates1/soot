# 调用点摘要的通用接入设计

## 生命周期与创建职责

客户端在方法首次可达且通过原有范围守卫之后，覆盖已有 `OnFlyCallGraphBuilder.processNewMethod()` 准备调用点摘要，再委托原处理流程。方法体来自预加载、懒加载还是合成代码，都使用这个入口；不包装 MethodSource，不在传播阶段换图，不扫描全 Scene 修补遗漏。

标准创建链分为三个对象职责：SparkTransformer 创建 PAG 构建器，PAG 构建器创建 OnFlyCallGraph，后者通过已有工厂创建 OnFlyCallGraphBuilder。保留前两层的小型 protected 创建方法，使客户端能实际使用已有底层入口；它们不执行模型逻辑，默认创建原来的类。

```text
标准 SparkTransformer 流程
  → createContextInsensitiveBuilder
    → createOnFlyCallGraph
      → 已有 createOnFlyCallGraphBuilder
        → 已有 processNewMethod：客户端准备摘要一次
```

客户端匹配状态属于本次构图实例，不保存为 Scene 全局 provider。XML 解释、API 合同、归属判断留在分析器；Soot 核心消费通用摘要。原有普通调用目标、库分类和方法体权限保持各自职责。

## 回调的逻辑调用身份

摘要产生的回调与原 API 调用使用同一个源语句，但不是同一个被调方法。按需分析必须保留这种区别：原 API 继续使用已有 callAssigns；调用点摘要另外登记逻辑调用，包含源语句、调用方、回调声明方法、接收对象和实际赋值对。

同一语句的一个 DirectTarget 对应一个逻辑调用；动态解析出的多个实现属于该调用的多个目标。回调声明由摘要中的声明类型及子签名解析，不能用某次解析得到的具体实现替代，避免破坏协变返回和多态解析。

ContextSensitiveInfo 以独立调用点 ID 消费模型记录，复用已有参数边和目标登记逻辑。保留普通 API 的身份及 receiver 元数据；不构造伪 Jimple 调用，不关闭调用图细化，不通过退回原指针结果掩盖精度问题。只在启用 cs-demand 时保存所需记录，不改变普通 SPARK 的赋值语义，也不改历史全局 GENERIC_FAKE 模型的行为。

## 实际引擎接入

引擎使用一个通用客户端 SparkTransformer 适配器，覆盖创建方法并把模型准备函数传给构图器；XML 会话提供该函数。关闭模式使用原路径。开启模式通过实际 cg pack 使用适配器；临时替换阶段时保留声明、选项和位置，并在成功或异常后恢复原阶段。直接调用 SparkTransformer 的测试及调用方显式使用会话提供的 transformer，保留原断言。

不覆盖调用方未知的自定义 transformer；未支持的构图模式应明确说明，不能悄悄跳过模型。仅为内部隔离验证使用新的本地依赖版本，不修改已发布版本或第一份权限策略。

临时阶段的生命周期包括 Pack 中的对象和 PhaseOptions 中以该对象为键的配置。只恢复 Pack 不能释放客户端会话；必须在 finally 中移除临时对象的配置，同时恢复原阶段及兄弟阶段的选项。RadioScenePack 在插入已启用阶段时会修改其他阶段的 enabled，因此先检查配置是否合法，再保存和恢复整个组的相关状态。清理 API 只移除显式选项，阶段自带的默认选项继续有效。

## 协议范围与验收

本次支持 on-the-fly SPARK、GENERIC_FAKE、单个 DirectTarget、直接引用参数映射。局部非空摘要覆盖该语句的默认摘要，null 明确抑制默认摘要，普通 API 边保留。方法体在进入分析后应稳定，任意事后重写和摘要失效不在本次协议内。

回调 receiver 使用 Jimple Local；null 常量没有可执行目标。其他非 Local receiver 必须得到明确诊断，不能以无结果的方式静默接受。此约束不限制直接参数映射中的合法引用常量。摘要显式声明的 receiver 类型用于约束合法动态目标；未声明类型时仍按协议的接收者类型与回调子签名解析。数组 receiver 也必须使用回调签名，不能回退到原 API 签名。

原 API 的返回值与摘要调用的效果独立。例如同一语句已有 ExpectedTypeTag 表达已知返回类型时，仍需保留回调 receiver 与参数传播；回调的返回值不会自动成为原 API 的返回值。这些语义必须同时体现在调用图、PAG 和按需上下文中。

验收必须覆盖：预加载、冷方法、构图期间新生成体；静态、虚调用、接口及 SPECIAL；多态和协变返回；回调 receiver/参数与后续虚调用；按需分析中两次调用的数据区分；局部抑制的完整构图结果；实际 XML、IFDS 正负例及 OFF 路径。测试用于检验这些不变量，不按样例名称添加生产分支。
