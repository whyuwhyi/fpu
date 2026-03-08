package FPUv2.utils

import chisel3._

class SharedDualMulIO extends Bundle {
  val a0 = Output(UInt(12.W))
  val b0 = Output(UInt(24.W))
  val a1 = Output(UInt(12.W))
  val b1 = Output(UInt(24.W))
  val en = Output(Bool())
  val prod0 = Input(UInt(36.W))
  val prod1 = Input(UInt(36.W))
}
