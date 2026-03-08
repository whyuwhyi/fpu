package FPUv2

import FPUv2.utils.{CustomLaneOps, EmptyFPUCtrl, FPUInput, TestFPUCtrl, vecFPUInput}
import FPUv2.utils.RoundingModes._
import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.VecLiterals.AddVecLiteralConstructor
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class PackedFPUTest extends AnyFlatSpec with ChiselScalatestTester {
  import TestArgs._

  private val depthWarp = 2
  private val softThread = 4
  private val hardThread = 4

  private val PackedAddF16x2 = CustomLaneOps.PackedAddF16x2
  private val PackedMulF16x2 = CustomLaneOps.PackedMulF16x2
  private val PackedFmaF16x2 = CustomLaneOps.PackedFmaF16x2
  private val PackedAddBf16x2 = CustomLaneOps.PackedAddBf16x2
  private val PackedMulBf16x2 = CustomLaneOps.PackedMulBf16x2
  private val PackedFmaBf16x2 = CustomLaneOps.PackedFmaBf16x2

  private val CvtFp32FromFp16 = CustomLaneOps.CvtFp32FromFp16
  private val CvtFp16FromFp32 = CustomLaneOps.CvtFp16FromFp32
  private val CvtFp32FromBf16 = CustomLaneOps.CvtFp32FromBf16
  private val CvtBf16FromFp32 = CustomLaneOps.CvtBf16FromFp32

  private def u32(x: Int): BigInt = BigInt(x.toLong & 0xffffffffL)

  private object VecInput {
    var count = 0
    def reset(): Unit = count = 0

    def apply(a: Seq[Int], b: Seq[Int], c: Seq[Int], op: UInt, rm: UInt = RNE): vecFPUInput[TestFPUCtrl] = {
      count = (count + 1) % 32
      (new vecFPUInput(softThread, len, new TestFPUCtrl(depthWarp, softThread))).Lit(
        _.data -> Vec(softThread, new FPUInput(len, EmptyFPUCtrl(), true)).Lit((0 until softThread).map { i =>
          i -> new FPUInput(len, EmptyFPUCtrl(), true).Lit(
            _.a -> u32(a(i)).U(32.W),
            _.b -> u32(b(i)).U(32.W),
            _.c -> u32(c(i)).U(32.W),
            _.op -> op,
            _.rm -> rm
          )
        }: _*),
        _.ctrl -> (new TestFPUCtrl(depthWarp, softThread)).Lit(
          _.regIndex -> count.U,
          _.vecMask -> ((1 << softThread) - 1).U,
          _.warpID -> 0.U,
          _.wvd -> true.B,
          _.wxd -> false.B
        )
      )
    }
  }

  private def runAndRead(
    d: VectorFPU[TestFPUCtrl],
    input: vecFPUInput[TestFPUCtrl],
    maxCycles: Int = 100
  ): Seq[(BigInt, BigInt)] = {
    d.io.in.valid.poke(false.B)
    d.io.out.ready.poke(true.B)
    d.clock.step()

    d.io.in.bits.poke(input)
    d.io.in.valid.poke(true.B)
    var cycles = 0
    while (!d.io.in.ready.peek().litToBoolean && cycles < maxCycles) {
      d.clock.step()
      cycles += 1
    }
    d.io.in.ready.expect(true.B)
    d.clock.step()
    d.io.in.valid.poke(false.B)

    cycles = 0
    while (!d.io.out.valid.peek().litToBoolean && cycles < maxCycles) {
      d.clock.step()
      cycles += 1
    }
    d.io.out.valid.expect(true.B)

    val out = (0 until softThread).map { idx =>
      val result = d.io.out.bits.data(idx).result.peek().litValue
      val fflags = d.io.out.bits.data(idx).fflags.peek().litValue
      (result & 0xffffffffL, fflags)
    }
    d.clock.step()
    out
  }

  it should "share one dual sliced multiplier across fp32 and packed paths" in {
    val sv = (new ChiselStage).emitSystemVerilog(new ScalarFPU(expWidth, precision, new TestFPUCtrl(depthWarp, softThread)))
    val dualMulInstances = """(?m)^\s*DualSlicedMantissaMul\s+""".r.findAllIn(sv).length
    val legacyMulInstances = """(?m)^\s*SlicedMantissaMul\s+""".r.findAllIn(sv).length
    assert(dualMulInstances == 1)
    assert(legacyMulInstances == 0)
  }

  behavior of "VectorFPU packed and vcvt"

  it should "convert fp16 lanes to fp32 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val input = VecInput(
        a = Seq.fill(softThread)(0),
        b = Seq(0x00003c00, 0x0000c000, 0x00004200, 0x00003800),
        c = Seq.fill(softThread)(0),
        op = CvtFp32FromFp16
      )
      val out = runAndRead(d, input)
      val expected = Seq(0x3f800000L, 0xc0000000L, 0x40400000L, 0x3f000000L)
      out.zip(expected).foreach { case ((result, flags), exp) =>
        assert(result == exp)
        assert(flags == 0)
      }
    }
  }

  it should "convert fp32 lanes to fp16 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val input = VecInput(
        a = Seq.fill(softThread)(0),
        b = Seq(floatBits(1.5f), floatBits(-2.0f), floatBits(3.0f), floatBits(0.5f)),
        c = Seq.fill(softThread)(0),
        op = CvtFp16FromFp32
      )
      val out = runAndRead(d, input)
      val expected = Seq(0x00003e00L, 0x0000c000L, 0x00004200L, 0x00003800L)
      out.zip(expected).foreach { case ((result, flags), exp) =>
        assert(result == exp)
        assert(flags == 0)
      }
    }
  }

  it should "convert bf16 lanes to fp32 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val input = VecInput(
        a = Seq.fill(softThread)(0),
        b = Seq(0x00003f80, 0x0000c000, 0x00004040, 0x00003f00),
        c = Seq.fill(softThread)(0),
        op = CvtFp32FromBf16
      )
      val out = runAndRead(d, input)
      val expected = Seq(0x3f800000L, 0xc0000000L, 0x40400000L, 0x3f000000L)
      out.zip(expected).foreach { case ((result, flags), exp) =>
        assert(result == exp)
        assert(flags == 0)
      }
    }
  }

  it should "convert fp32 lanes to bf16 lanes and honor rm" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val exactInput = VecInput(
        a = Seq.fill(softThread)(0),
        b = Seq(floatBits(1.5f), floatBits(-2.0f), floatBits(3.0f), floatBits(0.5f)),
        c = Seq.fill(softThread)(0),
        op = CvtBf16FromFp32
      )
      val exactOut = runAndRead(d, exactInput)
      val exactExpected = Seq(0x00003fc0L, 0x0000c000L, 0x00004040L, 0x00003f00L)
      exactOut.zip(exactExpected).foreach { case ((result, flags), exp) =>
        assert(result == exp)
        assert(flags == 0)
      }

      val inexactBits = 0x3f800001
      val rtzOut = runAndRead(d, VecInput(
        a = Seq.fill(softThread)(0),
        b = Seq.fill(softThread)(inexactBits),
        c = Seq.fill(softThread)(0),
        op = CvtBf16FromFp32,
        rm = RTZ
      ))
      rtzOut.foreach { case (result, _) =>
        assert(low16(result) == fp32ToBf16Bits(inexactBits, RTZ.litValue.toInt))
      }

      val rupOut = runAndRead(d, VecInput(
        a = Seq.fill(softThread)(0),
        b = Seq.fill(softThread)(inexactBits),
        c = Seq.fill(softThread)(0),
        op = CvtBf16FromFp32,
        rm = RUP
      ))
      rupOut.foreach { case (result, _) =>
        assert(low16(result) == fp32ToBf16Bits(inexactBits, RUP.litValue.toInt))
      }
    }
  }

  it should "add packed f16x2 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val input = VecInput(
        a = Seq(
          packLowHigh16(0x3c00, 0xc000),
          packLowHigh16(0x3800, 0x3e00),
          packLowHigh16(0x4000, 0x4200),
          packLowHigh16(0xbc00, 0x4400)
        ),
        b = Seq(
          packLowHigh16(0x4200, 0x3800),
          packLowHigh16(0x3c00, 0xc000),
          packLowHigh16(0x3c00, 0x3c00),
          packLowHigh16(0x4000, 0xbc00)
        ),
        c = Seq.fill(softThread)(0),
        op = PackedAddF16x2
      )
      val out = runAndRead(d, input)
      val expected = Seq(
        u32(packLowHigh16(0x4400, 0xbe00)),
        u32(packLowHigh16(0x3e00, 0xb800)),
        u32(packLowHigh16(0x4200, 0x4400)),
        u32(packLowHigh16(0x3c00, 0x4200))
      )
      out.zip(expected).foreach { case ((result, flags), exp) =>
        assert(result == exp)
        assert(flags == 0)
      }
    }
  }

  it should "multiply and fuse packed f16x2 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val mulInput = VecInput(
        a = Seq.fill(softThread)(packLowHigh16(0x4000, 0xc000)),
        b = Seq.fill(softThread)(packLowHigh16(0x4200, 0x3800)),
        c = Seq.fill(softThread)(0),
        op = PackedMulF16x2
      )
      val mulOut = runAndRead(d, mulInput)
      mulOut.foreach { case (result, flags) =>
        assert(result == u32(packLowHigh16(0x4600, 0xbc00)))
        assert(flags == 0)
      }

      val fmaInput = VecInput(
        a = Seq.fill(softThread)(packLowHigh16(0x4000, 0xc000)),
        b = Seq.fill(softThread)(packLowHigh16(0x4200, 0x3800)),
        c = Seq.fill(softThread)(packLowHigh16(0x3c00, 0x4400)),
        op = PackedFmaF16x2
      )
      val fmaOut = runAndRead(d, fmaInput)
      fmaOut.foreach { case (result, flags) =>
        assert(result == u32(packLowHigh16(0x4700, 0x4200)))
        assert(flags == 0)
      }
    }
  }

  it should "add packed bf16x2 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val input = VecInput(
        a = Seq(
          packLowHigh16(0x3f80, 0xc000),
          packLowHigh16(0x3f00, 0x3fc0),
          packLowHigh16(0x4000, 0x4040),
          packLowHigh16(0xbf80, 0x4080)
        ),
        b = Seq(
          packLowHigh16(0x4040, 0x3f00),
          packLowHigh16(0x3f80, 0xc000),
          packLowHigh16(0x3f80, 0x3f80),
          packLowHigh16(0x4000, 0xbf80)
        ),
        c = Seq.fill(softThread)(0),
        op = PackedAddBf16x2
      )
      val out = runAndRead(d, input)
      val expected = Seq(
        u32(packLowHigh16(0x4080, 0xbfc0)),
        u32(packLowHigh16(0x3fc0, 0xbf00)),
        u32(packLowHigh16(0x4040, 0x4080)),
        u32(packLowHigh16(0x3f80, 0x4040))
      )
      out.zip(expected).foreach { case ((result, flags), exp) =>
        assert(result == exp)
        assert(flags == 0)
      }
    }
  }

  it should "multiply and fuse packed bf16x2 lanes" in {
    test(new VectorFPU(expWidth, precision, softThread, hardThread, new TestFPUCtrl(depthWarp, softThread))) { d =>
      VecInput.reset()
      val mulInput = VecInput(
        a = Seq.fill(softThread)(packLowHigh16(0x4000, 0xc000)),
        b = Seq.fill(softThread)(packLowHigh16(0x4040, 0x3f00)),
        c = Seq.fill(softThread)(0),
        op = PackedMulBf16x2
      )
      val mulOut = runAndRead(d, mulInput)
      mulOut.foreach { case (result, flags) =>
        assert(result == u32(packLowHigh16(0x40c0, 0xbf80)))
        assert(flags == 0)
      }

      val fmaInput = VecInput(
        a = Seq.fill(softThread)(packLowHigh16(0x4000, 0xc000)),
        b = Seq.fill(softThread)(packLowHigh16(0x4040, 0x3f00)),
        c = Seq.fill(softThread)(packLowHigh16(0x3f80, 0x4080)),
        op = PackedFmaBf16x2
      )
      val fmaOut = runAndRead(d, fmaInput)
      fmaOut.foreach { case (result, flags) =>
        assert(result == u32(packLowHigh16(0x40e0, 0x4040)))
        assert(flags == 0)
      }
    }
  }
}
