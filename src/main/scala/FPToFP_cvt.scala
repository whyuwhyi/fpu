package FPUv2

import FPUv2.utils._
import chisel3._
import chisel3.util._
import fudian._

class FPToFP_cvt(ctrlGen: Data = EmptyFPUCtrl())
  extends FPUPipelineModule(32, ctrlGen) {
  override def latency = 1

  val isFP16ToFP32 = io.in.bits.op === "b000".U
  val isFP32ToFP16 = io.in.bits.op === "b001".U
  val isBF16ToFP32 = io.in.bits.op === "b010".U
  val isFP32ToBF16 = io.in.bits.op === "b011".U

  val fp16ToFp32 = Module(new FPToFP(5, 11, 8, 24))
  val fp32ToFp16 = Module(new FPToFP(8, 24, 5, 11))

  fp16ToFp32.io.in := io.in.bits.b
  fp16ToFp32.io.rm := io.in.bits.rm

  fp32ToFp16.io.in := io.in.bits.b
  fp32ToFp16.io.rm := io.in.bits.rm

  val bf16Source = io.in.bits.b(15, 0)
  val bf16Fp = FloatPoint.fromUInt(bf16Source, 8, 8)
  val bf16Decode = bf16Fp.decode
  val bf16ToFp32Result = Cat(bf16Source, 0.U(16.W))
  val bf16ToFp32Flags = Cat(bf16Decode.isSNaN, false.B, false.B, false.B, false.B)

  val fp32Fp = FloatPoint.fromUInt(io.in.bits.b, 8, 24)
  val fp32Decode = fp32Fp.decode
  val bf16Rounder = Module(new RoundingUnit(7))
  bf16Rounder.io.in := fp32Fp.sig.head(7)
  bf16Rounder.io.roundIn := fp32Fp.sig(15).asBool
  bf16Rounder.io.stickyIn := fp32Fp.sig(14, 0).orR
  bf16Rounder.io.signIn := fp32Fp.sign
  bf16Rounder.io.rm := io.in.bits.rm

  val bf16RoundedExp = Mux(fp32Fp.exp === 0.U, bf16Rounder.io.cout.asUInt, fp32Fp.exp + bf16Rounder.io.cout)
  val bf16Overflow = !fp32Decode.expIsOnes && fp32Fp.exp === FloatPoint.maxNormExp(8).U && bf16Rounder.io.cout
  val bf16Rmin = RoundingUnit.is_rmin(io.in.bits.rm, fp32Fp.sign)
  val bf16OverflowExp = Mux(bf16Rmin, "hfe".U(8.W), "hff".U(8.W))
  val bf16OverflowSig = Mux(bf16Rmin, Fill(7, 1.U(1.W)), 0.U(7.W))
  val bf16CommonExp = Mux(bf16Overflow, bf16OverflowExp, bf16RoundedExp)
  val bf16CommonSig = Mux(bf16Overflow, bf16OverflowSig, bf16Rounder.io.out)
  val bf16Common = Cat(0.U(16.W), fp32Fp.sign, bf16CommonExp, bf16CommonSig)
  val bf16Special = Cat(
    0.U(16.W),
    Mux(fp32Decode.isNaN,
      FloatPoint.defaultNaNUInt(8, 8),
      Cat(fp32Fp.sign, Fill(8, 1.U(1.W)), 0.U(7.W))
    )
  )
  val fp32ToBf16Result = Mux(fp32Decode.expIsOnes, bf16Special, bf16Common)
  val fp32ToBf16Flags = Mux(
    fp32Decode.expIsOnes,
    Cat(fp32Decode.isSNaN, false.B, false.B, false.B, false.B),
    Cat(false.B, false.B, bf16Overflow, false.B, bf16Rounder.io.inexact)
  )

  io.out.bits.result := MuxCase(0.U(32.W), Seq(
    isFP16ToFP32 -> S1Reg(fp16ToFp32.io.result),
    isFP32ToFP16 -> S1Reg(fp32ToFp16.io.result),
    isBF16ToFP32 -> S1Reg(bf16ToFp32Result),
    isFP32ToBF16 -> S1Reg(fp32ToBf16Result)
  ))
  io.out.bits.fflags := MuxCase(0.U(5.W), Seq(
    isFP16ToFP32 -> S1Reg(fp16ToFp32.io.fflags),
    isFP32ToFP16 -> S1Reg(fp32ToFp16.io.fflags),
    isBF16ToFP32 -> S1Reg(bf16ToFp32Flags),
    isFP32ToBF16 -> S1Reg(fp32ToBf16Flags)
  ))
  io.out.bits.ctrl.foreach(_ := S1Reg(io.in.bits.ctrl.get))
}
