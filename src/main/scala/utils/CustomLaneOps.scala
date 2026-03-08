package FPUv2.utils

import chisel3._
import chisel3.util._

object CustomLaneOps {
  val PackedAddF16x2 = "b100100".U(6.W) // 36
  val PackedMulF16x2 = "b100101".U(6.W) // 37
  val PackedFmaF16x2 = "b100110".U(6.W) // 38
  val PackedAddBf16x2 = "b100111".U(6.W) // 39
  val PackedMulBf16x2 = "b101000".U(6.W) // 40
  val PackedFmaBf16x2 = "b101001".U(6.W) // 41

  val CvtFp32FromFp16 = "b101010".U(6.W) // 42
  val CvtFp16FromFp32 = "b101011".U(6.W) // 43
  val CvtFp32FromBf16 = "b101100".U(6.W) // 44
  val CvtBf16FromFp32 = "b101101".U(6.W) // 45

  def isPacked(op: UInt): Bool = op === PackedAddF16x2 || op === PackedMulF16x2 ||
    op === PackedFmaF16x2 || op === PackedAddBf16x2 || op === PackedMulBf16x2 ||
    op === PackedFmaBf16x2

  def isVectorConvert(op: UInt): Bool = op === CvtFp32FromFp16 || op === CvtFp16FromFp32 ||
    op === CvtFp32FromBf16 || op === CvtBf16FromFp32

  def packedSubOp(op: UInt): UInt = MuxLookup(op, 0.U(3.W))(Seq(
    PackedAddF16x2 -> 0.U(3.W),
    PackedMulF16x2 -> 1.U(3.W),
    PackedFmaF16x2 -> 2.U(3.W),
    PackedAddBf16x2 -> 4.U(3.W),
    PackedMulBf16x2 -> 5.U(3.W),
    PackedFmaBf16x2 -> 6.U(3.W)
  ))

  def cvtSubOp(op: UInt): UInt = MuxLookup(op, 0.U(3.W))(Seq(
    CvtFp32FromFp16 -> 0.U(3.W),
    CvtFp16FromFp32 -> 1.U(3.W),
    CvtFp32FromBf16 -> 2.U(3.W),
    CvtBf16FromFp32 -> 3.U(3.W)
  ))
}
