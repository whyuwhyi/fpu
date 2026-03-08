# FPU Packed / VCVT 向量扩展实施计划

> 对后续执行者：本计划仅服务 `fpu/` 子目录本轮开发；按任务逐项执行，不改主仓 `doc/plan.md`。

## 结论

- 采用方案 A：保持 `VectorFPU` 是 `ScalarFPU` lane 阵列的现有结构，只在 lane 内补 `packed + vcvt` 的底层能力。
- 本轮只支持以下两类向量自定义指令：
  - `vadd/vmul/vfma.(f16x2|bf16x2)`
  - `vcvt.fp32.fp16`、`vcvt.fp16.fp32`、`vcvt.fp32.bf16`、`vcvt.bf16.fp32`
- `rm` 不固定为 `RNE`，继续透传现有输入控制口。
- 不新增独立标量 ISA 入口；`ScalarFPU` 的新增能力仅作为 `VectorFPU` lane 复用。
- 本轮不处理 `tensor core`、`shuffle`、独立 `SFU/UNFU`。

## 目标

在不推翻 `fpu` 现有拓扑的前提下，为每个 32-bit 向量 lane 增加两类能力：

1. 32-bit 容器内双 16-bit lane 的 `packed` 浮点运算；
2. `fp32/fp16/bf16` 间的逐 lane 格式转换。

验收标准：

- `VectorFPU` 能正确执行 `f16x2/bf16x2` 的 `add/mul/fma`；
- `VectorFPU` 能正确执行四种 `vcvt`；
- `rm` 在 `packed` 与 `vcvt` 路径都能生效；
- 既有 FP32 测试不回退。

## 架构方案

- 顶层结构保持不变：`VectorFPU` 继续负责 split / gather，lane 级计算继续由 `ScalarFPU` 子模块阵列完成。
- 在 lane 级新增两块能力：
  - `PackedArith`：负责 `f16x2/bf16x2` 的 `add/mul/fma`；
  - `VecCvt`：负责四种 `fp32/fp16/bf16` 转换。
- 乘法路径遵循本轮约束：将现有 FP32 尾数乘法路径按 24-bit 尾数拆成高/低两段 `12x24` slice；packed 模式下直接服务两路 16-bit lane。
- 除乘法 slice 组织外，其余分类、规格化、舍入、异常尽量继续复用 `XiangShan` 的 `fudian` 现有模块。

## 技术栈

- `Chisel`
- `fudian`
- `mill`
- `chiseltest`

## 非目标

- 不在本轮补 `TensorCore` / `mma`
- 不在本轮补 `shuffle`
- 不在本轮把 `SFU/UNFU` 并回 `fpu`
- 不在本轮引入新的高层 ISA 前端；默认仍由外部向量控制路径提供 custom op

## 任务拆分

### 任务 1：补齐操作编码与测试辅助

**文件：**
- 修改：`fpu/src/main/scala/utils/FPUOps.scala`
- 修改：`fpu/src/test/scala/TestUtils.scala`
- 创建：`fpu/src/test/scala/PackedFPUTest.scala`

**步骤：**
1. 在 `FPUOps.scala` 中为 lane 内复用新增 `PackedArith` 与 `VecCvt` 的 op 编码。
2. 保持现有 `ScalarFPU` 6-bit 顶层 op + 3-bit 子 op 的分层方式，不改普通 FP32 旧编码。
3. 在 `TestUtils.scala` 中补 `fp16` / `bf16` 的打包与拆包辅助函数，支持生成 `f16x2` / `bf16x2` 32-bit 容器。
4. 在 `PackedFPUTest.scala` 中先写失败用例，至少覆盖：
   - `vadd.f16x2`
   - `vmul.f16x2`
   - `vfma.f16x2`
   - `vadd.bf16x2`
   - `vmul.bf16x2`
   - `vfma.bf16x2`
   - `vcvt.fp32.fp16`
   - `vcvt.fp16.fp32`
   - `vcvt.fp32.bf16`
   - `vcvt.bf16.fp32`
5. 先只跑新测试，确认当前实现确实失败。

**建议命令：**
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.PackedFPUTest`

**预期：**
- 编译通过但测试失败，或编译直接失败于缺少实现；无论哪种都算进入下一任务的有效起点。

### 任务 2：扩展 `FPToFP_cvt` 支持 BF16

**文件：**
- 修改：`fpu/src/main/scala/FPToFP_cvt.scala`
- 修改：`fpu/src/main/scala/utils/FPUOps.scala`
- 测试：`fpu/src/test/scala/PackedFPUTest.scala`

**步骤：**
1. 将 `FPToFP_cvt.scala` 从当前两种转换扩成四种转换。
2. 新增两组 `fudian.FPToFP` 实例：
   - `bf16 -> fp32`
   - `fp32 -> bf16`
3. 明确 `bf16` 参数使用：`expWidth=8`，`precision=8`。
4. 保持 `rm` 透传，不写死 `RNE`。
5. 对 `fp32 -> fp16/bf16` 结果明确采用 32-bit 容器承载；低 16 位有效，高 16 位清零。
6. 跑 `vcvt` 定向测试，先收敛四种转换全部通过。

**建议命令：**
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.PackedFPUTest`

**预期：**
- 四种 `vcvt` 通过；`packed add/mul/fma` 仍可能失败。

### 任务 3：抽出共享 `12x24` 尾数乘法 slice

**文件：**
- 创建：`fpu/src/main/scala/utils/SlicedMantissaMul.scala`
- 修改：`fpu/src/main/scala/FMA.scala`
- 测试：`fpu/src/test/scala/PackedFPUTest.scala`

**步骤：**
1. 新建共享乘法器模块，统一封装以下两种模式：
   - `fp32` 模式：用两段 `12x24` 组合出原本的 24x24 尾数乘法结果；
   - `packed` 模式：两路 `12x24` 分别服务两个 16-bit lane。
2. 保持现有流水寄存控制接口风格，继续使用 `regEnable(1)` 之类的使能。
3. 在 `FMA.scala` 中将 `NaiveMultiplier(precision + 1)` 替换成新的 slice 乘法组织。
4. 先保证 FP32 `mul/fma` 旧行为不回退，再接 `packed`。
5. 增加至少一组“旧 FP32 case 未回退”的回归断言。

**建议命令：**
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.FPUTest`
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.PackedFPUTest`

**预期：**
- 既有 `FPUTest` 不回退；新测试中与 `mul/fma` 直接相关的 case 开始转绿。

### 任务 4：实现 `PackedArith` lane 模块

**文件：**
- 创建：`fpu/src/main/scala/PackedFPU.scala`
- 修改：`fpu/src/main/scala/FMA.scala`
- 修改：`fpu/src/main/scala/utils/FPUSubModule.scala`
- 测试：`fpu/src/test/scala/PackedFPUTest.scala`

**步骤：**
1. 在 `PackedFPU.scala` 中实现 32-bit 容器拆包/封包工具：
   - `f16x2`
   - `bf16x2`
2. 为每个 16-bit lane 建立独立的分类、特殊值、规格化与舍入处理。
3. `add` 路径尽量复用 `fudian` 的加法阶段模块，而不是重写完整浮点加法器。
4. `mul` 路径接任务 3 的共享 slice 乘法器。
5. `fma` 路径复用“乘法结果送加法器”的现有组织，语义固定为 `vd = vs1 * vs2 + vd`。
6. 两路 lane 的 `fflags` 按位 OR 汇总到单个 5-bit 输出。
7. 跑 `f16x2` 全部 case，确认 `add/mul/fma` 先通过。

**建议命令：**
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.PackedFPUTest`

**预期：**
- `f16x2` 通过；`bf16x2` 可能仍残留问题。

### 任务 5：补齐 `bf16x2` 路径并接入 `ScalarFPU`

**文件：**
- 修改：`fpu/src/main/scala/PackedFPU.scala`
- 修改：`fpu/src/main/scala/FPU.scala`
- 修改：`fpu/src/main/scala/utils/FPUOps.scala`
- 测试：`fpu/src/test/scala/PackedFPUTest.scala`

**步骤：**
1. 在 `PackedFPU.scala` 中补齐 `bf16x2` 的 lane 解释与舍入边界。
2. 在 `ScalarFPU` 的子模块表中接入：
   - `PackedArith`
   - `VecCvt`
3. 维持 `VectorFPU` 拓扑不变，只让它通过 lane 阵列复用新能力。
4. 不新增独立标量 custom 指令对外入口；仅在内部编码层面支持新子模块。
5. 跑 `PackedFPUTest` 全量，确认 `f16x2/bf16x2 + vcvt` 全通过。

**建议命令：**
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.PackedFPUTest`

**预期：**
- 所有新加向量 case 通过。

### 任务 6：回归旧测试并清理收尾

**文件：**
- 修改：`fpu/src/test/scala/FPUTest.scala`
- 修改：`fpu/src/test/scala/FMATest.scala`
- 修改：`fpu/plan.md`

**步骤：**
1. 跑既有 `FPUTest`，确认普通 FP32 路径不回退。
2. 跑既有 `FMATest`，确认 `FMA` 原有行为不回退。
3. 如有必要，在 `FPUTest.scala` 中补一组最小 smoke case，证明旧路径与新路径可共存。
4. 将实际通过的命令、剩余风险、后续 tensor core 待办回填到本文件末尾。

**建议命令：**
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.FPUTest`
- `cd fpu && ./mill -i fpuv2[chisel3].test.testOnly FPUv2.FMATest`
- `cd fpu && ./mill -i -j 0 __.compile`

**预期：**
- 新旧测试全部通过；若 `__.compile` 失败，必须先区分是本轮改动还是仓库既有问题，再决定是否继续修复。

## 风险点

- `fudian` 现有模块大多按单路浮点格式组织；`packed` 复用时，最容易踩坑的是 lane 级特殊值与 `fflags` 汇总。
- `12x24` slice 复用若处理不当，可能导致 FP32 原路径时序或数值回退；因此任务 3 必须先保住旧测试。
- `bf16` 与 `fp16` 共享 16-bit 容器，但指数/尾数宽度不同，不能只靠 `fp16` 路径简单改常量。
- `vfma` 的累加源来自 `vd` 旧值；测试必须覆盖正负零、NaN、Inf、subnormal、不同 `rm`。

## 行动项

1. 先落任务 1，写出失败测试与编码骨架。
2. 优先打通四种 `vcvt`，因为这部分最独立、风险最低。
3. 再改乘法 slice 与 `packed mul/fma`，每步都回归旧 FP32 测试。
4. 最后把实际验证结果回填到本文件。

## 当前进度（2026-03-07）

### 已完成
- `vcvt` 四个方向已接通：`fp16 <-> fp32`、`bf16 <-> fp32`
- `packed` 六个算子已接通：`add/mul/fma` × `{f16x2,bf16x2}`
- `FMUL` 已切到新的 `12x24` slice 乘法器组织
- `PackedFPUTest` 已落地并通过定向用例验证

### 待继续确认
- 旧 `VectorFPU` 的 `Split and Gather` smoke 仍过慢；需要换成更小的定向 FP32 回归来完成最终收尾

### 2026-03-08 修正
- 已把上一轮“`packed` 内部独立乘法器”的实现修正为真正的顶层共享结构
- 当前 `ScalarFPU` 内仅保留一颗 `DualSlicedMantissaMul`，由 `FP32` 与 `packed` 共同复用
- `FMATest` 与 `packed/vcvt` 关键回归已重新通过
