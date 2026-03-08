package FPUv2.utils

import chisel3._
import chisel3.util._

class DualSlicedMantissaMul(pipeAt: Seq[Int]) extends Module {
  require(pipeAt.size == 1)

  val io = IO(new Bundle() {
    val a0 = Input(UInt(12.W))
    val b0 = Input(UInt(24.W))
    val a1 = Input(UInt(12.W))
    val b1 = Input(UInt(24.W))
    val regEnables = Input(Vec(pipeAt.size, Bool()))
    val prod0 = Output(UInt(36.W))
    val prod1 = Output(UInt(36.W))
  })

  val a0Reg = RegEnable(io.a0, io.regEnables(0))
  val b0Reg = RegEnable(io.b0, io.regEnables(0))
  val a1Reg = RegEnable(io.a1, io.regEnables(0))
  val b1Reg = RegEnable(io.b1, io.regEnables(0))

  io.prod0 := a0Reg * b0Reg
  io.prod1 := a1Reg * b1Reg
}
