package FPUv2

import chisel3._
import chisel3.util._
import fudian.utils._
import fudian.{FCMA_ADD_s1, FCMA_ADD_s2, FMULToFADD, FMUL_s1, FMUL_s2, FMUL_s3, RawFloat}
import FPUv2.utils._
import FPUv2.utils.FPUOps._

class NaiveMultiplier(len: Int, pipeAt: Seq[Int]) extends Module {
  val io = IO(new Bundle() {
    val a, b = Input(UInt(len.W))
    val regEnables = Input(Vec(pipeAt.size, Bool()))
    val result = Output(UInt((2 * len).W))
    val sum = Output(UInt(len.W))
    val carry = Output(UInt(len.W))
  })
  io.result := RegEnable(io.a, io.regEnables(0)) * RegEnable(io.b, io.regEnables(0))
  io.sum := 0.U
  io.carry := 0.U
}

class MulToAddIO(expWidth: Int, precision: Int, ctrlGen: Data = EmptyFPUCtrl()) extends Bundle {
  val mulOutput = new FMULToFADD(expWidth, precision)
  val addAnother = UInt((expWidth + precision).W)
  val op = UInt(3.W)
  val rm = UInt(3.W)
  val ctrl = FPUCtrlFac(ctrlGen)
}

class FMULPipe(expWidth: Int, precision: Int, ctrlGen: Data = EmptyFPUCtrl(), useExternalSharedMul: Boolean = false)
  extends FPUPipelineModule(expWidth + precision, ctrlGen) {
  override def latency: Int = 2
  require(precision <= 24)

  val toAdd = IO(Output(new MulToAddIO(expWidth, precision, ctrlGen)))
  val sharedMul = IO(new SharedDualMulIO)

  val s1 = Module(new FMUL_s1(expWidth, precision))
  val s2 = Module(new FMUL_s2(expWidth, precision))
  val s3 = Module(new FMUL_s3(expWidth, precision))

  val invProd = withInvProd(io.in.bits.op)

  s1.io.a := io.in.bits.a
  s1.io.b := Mux(invProd, invertSign(io.in.bits.b), io.in.bits.b)
  s1.io.rm := io.in.bits.rm

  s2.io.in := S1Reg(s1.io.out)
  s3.io.in := S2Reg(s2.io.out)

  val raw_a = RawFloat.fromUInt(s1.io.a, s1.expWidth, s1.precision)
  val raw_b = RawFloat.fromUInt(s1.io.b, s1.expWidth, s1.precision)

  private val splitPoint = math.min(12, precision)
  private val lowerA =
    if (precision >= 12) raw_a.sig(11, 0)
    else Cat(0.U((12 - precision).W), raw_a.sig)
  private val upperA =
    if (precision > 12) {
      val upperWidth = precision - 12
      if (upperWidth < 12) Cat(0.U((12 - upperWidth).W), raw_a.sig(precision - 1, 12))
      else raw_a.sig(precision - 1, 12)
    } else 0.U(12.W)
  private val bWide =
    if (precision < 24) Cat(0.U((24 - precision).W), raw_b.sig)
    else raw_b.sig(23, 0)

  sharedMul.a0 := lowerA
  sharedMul.a1 := upperA
  sharedMul.b0 := bWide
  sharedMul.b1 := bWide
  sharedMul.en := regEnable(1)

  val prod0 = Wire(UInt(36.W))
  val prod1 = Wire(UInt(36.W))
  if (useExternalSharedMul) {
    prod0 := sharedMul.prod0
    prod1 := sharedMul.prod1
  } else {
    val internalMul = Module(new DualSlicedMantissaMul(Seq(1)))
    internalMul.io.a0 := sharedMul.a0
    internalMul.io.a1 := sharedMul.a1
    internalMul.io.b0 := sharedMul.b0
    internalMul.io.b1 := sharedMul.b1
    internalMul.io.regEnables(0) := sharedMul.en
    prod0 := internalMul.io.prod0
    prod1 := internalMul.io.prod1
  }

  val combinedProd = prod0 + (prod1 << splitPoint)
  s2.io.prod := combinedProd(2 * precision - 1, 0)

  toAdd.ctrl.foreach(_ := S2Reg(S1Reg(io.in.bits.ctrl.get)))
  toAdd.addAnother := S2Reg(S1Reg(io.in.bits.c))
  toAdd.mulOutput := s3.io.to_fadd
  toAdd.op := S2Reg(S1Reg(io.in.bits.op))
  toAdd.rm := S2Reg(S1Reg(io.in.bits.rm))
  io.out.bits.result := s3.io.result
  io.out.bits.fflags := s3.io.fflags
  io.out.bits.ctrl.foreach(_ := toAdd.ctrl.get)
}

class FADDPipe(expWidth: Int, precision: Int, ctrlGen: Data = EmptyFPUCtrl())
  extends FPUPipelineModule(expWidth + precision, ctrlGen) {
  override def latency: Int = 1

  val len = expWidth + precision

  val fromMul = IO(Input(new MulToAddIO(expWidth, precision, ctrlGen)))

  val s1 = Module(new FCMA_ADD_s1(expWidth, 2 * precision, precision))
  val s2 = Module(new FCMA_ADD_s2(expWidth, 2 * precision, precision))

  val isFMA = FPUOps.isFMA(io.in.bits.op)

  val srcA = io.in.bits.a
  val srcB = Mux(isFMA, fromMul.addAnother, io.in.bits.b)

  val invAdd = withSUB(io.in.bits.op)

  val add1 = Mux(isFMA,
    fromMul.mulOutput.fp_prod.asUInt,
    Cat(srcA(len - 1, 0), 0.U(precision.W))
  )
  val add2 = Cat(
    Mux(invAdd, invertSign(srcB), srcB),
    0.U(precision.W)
  )
  s1.io.a := add1
  s1.io.b := add2
  s1.io.b_inter_valid := isFMA
  s1.io.b_inter_flags := Mux(isFMA,
    fromMul.mulOutput.inter_flags,
    0.U.asTypeOf(s1.io.b_inter_flags)
  )
  s1.io.rm := Mux(isFMA, fromMul.rm, io.in.bits.rm)
  s2.io.in := S1Reg(s1.io.out)

  io.out.bits.result := s2.io.result
  io.out.bits.fflags := s2.io.fflags
  io.out.bits.ctrl.foreach(_ := S1Reg(io.in.bits.ctrl.get))
}

class FMA(expWidth: Int, precision: Int, ctrlGen: Data = EmptyFPUCtrl(), useExternalSharedMul: Boolean = false)
  extends FPUSubModule(expWidth + precision, ctrlGen) {

  val sharedMul = IO(new SharedDualMulIO)
  val packedMode = IO(Input(Bool()))

  val mulPipe = Module(new FMULPipe(expWidth, precision, ctrlGen, useExternalSharedMul))
  val addPipe = Module(new FADDPipe(expWidth, precision, ctrlGen))
  val packedMulPipe = Module(new PackedMulPipe(ctrlGen))
  val fp16Add0 = Module(new FADDPipe(5, 11, ctrlGen))
  val fp16Add1 = Module(new FADDPipe(5, 11, ctrlGen))
  val bf16Add0 = Module(new FADDPipe(8, 8, ctrlGen))
  val bf16Add1 = Module(new FADDPipe(8, 8, ctrlGen))

  private val waitIdle :: waitAddFp16 :: waitAddBf16 :: waitMul :: waitFmaFp16 :: waitFmaBf16 :: Nil = Enum(6)
  private val packedState = RegInit(waitIdle)
  private val packedBusy = packedState =/= waitIdle
  private val selectPackedReq = packedBusy || (io.in.valid && packedMode)

  sharedMul.a0 := Mux(selectPackedReq, packedMulPipe.sharedMul.a0, mulPipe.sharedMul.a0)
  sharedMul.b0 := Mux(selectPackedReq, packedMulPipe.sharedMul.b0, mulPipe.sharedMul.b0)
  sharedMul.a1 := Mux(selectPackedReq, packedMulPipe.sharedMul.a1, mulPipe.sharedMul.a1)
  sharedMul.b1 := Mux(selectPackedReq, packedMulPipe.sharedMul.b1, mulPipe.sharedMul.b1)
  sharedMul.en := Mux(selectPackedReq, packedMulPipe.sharedMul.en, mulPipe.sharedMul.en)
  mulPipe.sharedMul.prod0 := sharedMul.prod0
  mulPipe.sharedMul.prod1 := sharedMul.prod1
  packedMulPipe.sharedMul.prod0 := sharedMul.prod0
  packedMulPipe.sharedMul.prod1 := sharedMul.prod1

  val normalInValid = io.in.valid && !packedMode
  mulPipe.io.in.bits := io.in.bits
  mulPipe.io.in.valid := normalInValid && (FPUOps.isFMA(io.in.bits.op) || FPUOps.isFMUL(io.in.bits.op))

  class ArbiterIO extends Bundle {
    val ctrl = FPUCtrlFac(ctrlGen.cloneType)
    val op = UInt(3.W)
  }

  val toAddArbiter = Module(new Arbiter(new ArbiterIO, 2))
  val toAddArbiterFIFO = Seq.fill(2)(Module(new Queue(new ArbiterIO, entries = 1, pipe = true)))
  toAddArbiterFIFO(1).io.enq.bits.op := io.in.bits.op(2,0)
  toAddArbiterFIFO(1).io.enq.bits.ctrl.foreach(_ := io.in.bits.ctrl.get)
  toAddArbiterFIFO(0).io.enq.bits.op := mulPipe.toAdd.op
  toAddArbiterFIFO(0).io.enq.bits.ctrl.foreach(_ := mulPipe.toAdd.ctrl.get)
  toAddArbiterFIFO(1).io.enq.valid := normalInValid && FPUOps.isADDSUB(io.in.bits.op)
  toAddArbiterFIFO(0).io.enq.valid := FPUOps.isFMA(mulPipe.toAdd.op) && mulPipe.io.out.valid
  toAddArbiter.io.in(0) <> toAddArbiterFIFO(0).io.deq
  toAddArbiter.io.in(1) <> toAddArbiterFIFO(1).io.deq
  addPipe.io.in.bits.ctrl.foreach(_ := toAddArbiter.io.out.bits.ctrl.get)

  val inToAddFIFO = Module(new Queue(io.in.bits.cloneType, entries = 1, pipe = true))
  inToAddFIFO.io.enq.bits := io.in.bits
  inToAddFIFO.io.enq.valid := normalInValid && FPUOps.isADDSUB(io.in.bits.op)
  addPipe.io.in.bits := inToAddFIFO.io.deq.bits
  addPipe.io.in.bits.op := toAddArbiter.io.out.bits.op
  inToAddFIFO.io.deq.ready := toAddArbiter.io.in(1).ready

  val mulToAddFIFO = Module(new Queue(new MulToAddIO(expWidth, precision, ctrlGen), entries = 1, pipe = true))
  mulToAddFIFO.io.enq.bits := mulPipe.toAdd
  mulToAddFIFO.io.enq.valid := toAddArbiterFIFO(0).io.enq.fire
  addPipe.fromMul := mulToAddFIFO.io.deq.bits
  mulToAddFIFO.io.deq.ready := toAddArbiter.io.in(0).ready

  toAddArbiter.io.out.ready := addPipe.io.in.ready
  addPipe.io.in.valid := toAddArbiter.io.out.valid
  addPipe.io.in.bits.ctrl.foreach(_ := toAddArbiter.io.out.bits.ctrl.get)

  val normalReady = Mux(FPUOps.isADDSUB(io.in.bits.op), toAddArbiterFIFO(1).io.enq.ready, mulPipe.io.in.ready)
  val mulFIFO = Module(new Queue(new FPUOutput(expWidth + precision, ctrlGen), entries = 1, pipe = true))
  val addFIFO = Module(new Queue(new FPUOutput(expWidth + precision, ctrlGen), entries = 1, pipe = true))
  mulFIFO.io.enq.bits := mulPipe.io.out.bits
  mulFIFO.io.enq.valid := mulPipe.io.out.valid && FPUOps.isFMUL(mulPipe.toAdd.op)
  addFIFO.io.enq <> addPipe.io.out

  mulPipe.io.out.ready := (toAddArbiterFIFO(0).io.enq.ready && FPUOps.isFMA(mulPipe.toAdd.op)) ||
    (mulFIFO.io.enq.ready && FPUOps.isFMUL(mulPipe.toAdd.op))

  val normalOutArbiter = Module(new Arbiter(new FPUOutput(expWidth + precision, ctrlGen), 2))
  normalOutArbiter.io.in(0) <> addFIFO.io.deq
  normalOutArbiter.io.in(1) <> mulFIFO.io.deq

  private def zeroMulToAdd(inExpWidth: Int, inPrecision: Int) = 0.U.asTypeOf(new MulToAddIO(inExpWidth, inPrecision, ctrlGen))
  private val zeroFp16Mul = zeroMulToAdd(5, 11)
  private val zeroBf16Mul = zeroMulToAdd(8, 8)
  private val packedIsBF16 = io.in.bits.op(2)
  private val packedOpKind = io.in.bits.op(1, 0)
  private val packedIsAdd = packedOpKind === "b00".U
  private val packedIsMul = packedOpKind === "b01".U
  private val packedIsFMA = packedOpKind === "b10".U

  private def driveAddPipe(
    pipe: FADDPipe,
    directValid: Bool,
    directA: UInt,
    directB: UInt,
    fmaValid: Bool,
    fromMul: MulToAddIO,
    zeroMul: MulToAddIO
  ): Unit = {
    pipe.io.in.bits.a := Mux(fmaValid, 0.U(16.W), directA)
    pipe.io.in.bits.b := Mux(fmaValid, 0.U(16.W), directB)
    pipe.io.in.bits.c := 0.U
    pipe.io.in.bits.op := Mux(fmaValid, FN_FMADD(2, 0), FN_FADD(2, 0))
    pipe.io.in.bits.rm := Mux(fmaValid, fromMul.rm, io.in.bits.rm)
    pipe.io.in.bits.ctrl.foreach(_ := Mux(fmaValid, fromMul.ctrl.get, io.in.bits.ctrl.get))
    pipe.io.in.valid := directValid || fmaValid
    pipe.fromMul := Mux(fmaValid, fromMul, zeroMul)
  }

  val packedAddReady = fp16Add0.io.in.ready && fp16Add1.io.in.ready
  val packedBf16AddReady = bf16Add0.io.in.ready && bf16Add1.io.in.ready
  val packedSelectedReady = Mux(packedIsAdd, Mux(packedIsBF16, packedBf16AddReady, packedAddReady), packedMulPipe.io.in.ready)
  val packedIssue = !packedBusy && io.in.valid && packedMode && packedSelectedReady
  when(packedIssue) {
    when(packedIsAdd && !packedIsBF16) { packedState := waitAddFp16 }
      .elsewhen(packedIsAdd && packedIsBF16) { packedState := waitAddBf16 }
      .elsewhen(packedIsMul) { packedState := waitMul }
      .elsewhen(packedIsFMA && !packedIsBF16) { packedState := waitFmaFp16 }
      .elsewhen(packedIsFMA && packedIsBF16) { packedState := waitFmaBf16 }
  }
  when(io.out.fire && packedBusy) {
    packedState := waitIdle
  }

  packedMulPipe.io.in.bits := io.in.bits
  packedMulPipe.io.in.valid := !packedBusy && io.in.valid && packedMode && (packedIsMul || packedIsFMA)
  packedMulPipe.io.out.ready := MuxLookup(packedState, false.B)(Seq(
    waitMul -> io.out.ready,
    waitFmaFp16 -> packedAddReady,
    waitFmaBf16 -> packedBf16AddReady
  ))

  val fp16DirectAddValid = !packedBusy && io.in.valid && packedMode && packedIsAdd && !packedIsBF16
  val bf16DirectAddValid = !packedBusy && io.in.valid && packedMode && packedIsAdd && packedIsBF16
  val fp16FmaValid = packedState === waitFmaFp16 && packedMulPipe.io.out.valid
  val bf16FmaValid = packedState === waitFmaBf16 && packedMulPipe.io.out.valid

  driveAddPipe(fp16Add0, fp16DirectAddValid, io.in.bits.a(15, 0), io.in.bits.b(15, 0), fp16FmaValid, packedMulPipe.toAddFp16(0), zeroFp16Mul)
  driveAddPipe(fp16Add1, fp16DirectAddValid, io.in.bits.a(31, 16), io.in.bits.b(31, 16), fp16FmaValid, packedMulPipe.toAddFp16(1), zeroFp16Mul)
  driveAddPipe(bf16Add0, bf16DirectAddValid, io.in.bits.a(15, 0), io.in.bits.b(15, 0), bf16FmaValid, packedMulPipe.toAddBf16(0), zeroBf16Mul)
  driveAddPipe(bf16Add1, bf16DirectAddValid, io.in.bits.a(31, 16), io.in.bits.b(31, 16), bf16FmaValid, packedMulPipe.toAddBf16(1), zeroBf16Mul)

  val fp16AddOutValid = fp16Add0.io.out.valid && fp16Add1.io.out.valid
  val bf16AddOutValid = bf16Add0.io.out.valid && bf16Add1.io.out.valid
  fp16Add0.io.out.ready := io.out.ready && (packedState === waitAddFp16 || packedState === waitFmaFp16)
  fp16Add1.io.out.ready := io.out.ready && (packedState === waitAddFp16 || packedState === waitFmaFp16)
  bf16Add0.io.out.ready := io.out.ready && (packedState === waitAddBf16 || packedState === waitFmaBf16)
  bf16Add1.io.out.ready := io.out.ready && (packedState === waitAddBf16 || packedState === waitFmaBf16)

  val packedOutValid = MuxLookup(packedState, false.B)(Seq(
    waitAddFp16 -> fp16AddOutValid,
    waitAddBf16 -> bf16AddOutValid,
    waitMul -> packedMulPipe.io.out.valid,
    waitFmaFp16 -> fp16AddOutValid,
    waitFmaBf16 -> bf16AddOutValid
  ))
  val packedOutBits = Wire(new FPUOutput(expWidth + precision, ctrlGen))
  packedOutBits.result := MuxLookup(packedState, 0.U((expWidth + precision).W))(Seq(
    waitAddFp16 -> Cat(fp16Add1.io.out.bits.result, fp16Add0.io.out.bits.result),
    waitAddBf16 -> Cat(bf16Add1.io.out.bits.result, bf16Add0.io.out.bits.result),
    waitMul -> packedMulPipe.io.out.bits.result,
    waitFmaFp16 -> Cat(fp16Add1.io.out.bits.result, fp16Add0.io.out.bits.result),
    waitFmaBf16 -> Cat(bf16Add1.io.out.bits.result, bf16Add0.io.out.bits.result)
  ))
  packedOutBits.fflags := MuxLookup(packedState, 0.U(5.W))(Seq(
    waitAddFp16 -> (fp16Add0.io.out.bits.fflags | fp16Add1.io.out.bits.fflags),
    waitAddBf16 -> (bf16Add0.io.out.bits.fflags | bf16Add1.io.out.bits.fflags),
    waitMul -> packedMulPipe.io.out.bits.fflags,
    waitFmaFp16 -> (fp16Add0.io.out.bits.fflags | fp16Add1.io.out.bits.fflags),
    waitFmaBf16 -> (bf16Add0.io.out.bits.fflags | bf16Add1.io.out.bits.fflags)
  ))
  packedOutBits.ctrl.foreach { ctrl =>
    ctrl := MuxLookup(packedState, 0.U.asTypeOf(ctrl))(Seq(
      waitAddFp16 -> fp16Add0.io.out.bits.ctrl.get,
      waitAddBf16 -> bf16Add0.io.out.bits.ctrl.get,
      waitMul -> packedMulPipe.io.out.bits.ctrl.get,
      waitFmaFp16 -> fp16Add0.io.out.bits.ctrl.get,
      waitFmaBf16 -> bf16Add0.io.out.bits.ctrl.get
    ))
  }

  io.in.ready := Mux(packedMode, !packedBusy && packedSelectedReady, normalReady)
  normalOutArbiter.io.out.ready := io.out.ready && !packedBusy
  io.out.valid := Mux(packedBusy, packedOutValid, normalOutArbiter.io.out.valid)
  io.out.bits := Mux(packedBusy, packedOutBits, normalOutArbiter.io.out.bits)
}


class PackedMulPipe(ctrlGen: Data = EmptyFPUCtrl()) extends FPUPipelineModule(32, ctrlGen) {
  override def latency: Int = 2

  val sharedMul = IO(new SharedDualMulIO)
  val toAddFp16 = IO(Output(Vec(2, new MulToAddIO(5, 11, ctrlGen))))
  val toAddBf16 = IO(Output(Vec(2, new MulToAddIO(8, 8, ctrlGen))))

  private val isBF16 = io.in.bits.op(2)
  private val lane0A = io.in.bits.a(15, 0)
  private val lane1A = io.in.bits.a(31, 16)
  private val lane0B = io.in.bits.b(15, 0)
  private val lane1B = io.in.bits.b(31, 16)
  private val lane0C = io.in.bits.c(15, 0)
  private val lane1C = io.in.bits.c(31, 16)

  private val fp16s10 = Module(new FMUL_s1(5, 11))
  private val fp16s11 = Module(new FMUL_s1(5, 11))
  private val fp16s20 = Module(new FMUL_s2(5, 11))
  private val fp16s21 = Module(new FMUL_s2(5, 11))
  private val fp16s30 = Module(new FMUL_s3(5, 11))
  private val fp16s31 = Module(new FMUL_s3(5, 11))

  private val bf16s10 = Module(new FMUL_s1(8, 8))
  private val bf16s11 = Module(new FMUL_s1(8, 8))
  private val bf16s20 = Module(new FMUL_s2(8, 8))
  private val bf16s21 = Module(new FMUL_s2(8, 8))
  private val bf16s30 = Module(new FMUL_s3(8, 8))
  private val bf16s31 = Module(new FMUL_s3(8, 8))

  Seq((fp16s10, lane0A, lane0B), (fp16s11, lane1A, lane1B)).foreach { case (s1, a, b) =>
    s1.io.a := a
    s1.io.b := b
    s1.io.rm := io.in.bits.rm
  }
  Seq((bf16s10, lane0A, lane0B), (bf16s11, lane1A, lane1B)).foreach { case (s1, a, b) =>
    s1.io.a := a
    s1.io.b := b
    s1.io.rm := io.in.bits.rm
  }

  private val fp16RawA0 = RawFloat.fromUInt(lane0A, 5, 11)
  private val fp16RawA1 = RawFloat.fromUInt(lane1A, 5, 11)
  private val fp16RawB0 = RawFloat.fromUInt(lane0B, 5, 11)
  private val fp16RawB1 = RawFloat.fromUInt(lane1B, 5, 11)
  private val bf16RawA0 = RawFloat.fromUInt(lane0A, 8, 8)
  private val bf16RawA1 = RawFloat.fromUInt(lane1A, 8, 8)
  private val bf16RawB0 = RawFloat.fromUInt(lane0B, 8, 8)
  private val bf16RawB1 = RawFloat.fromUInt(lane1B, 8, 8)

  sharedMul.a0 := Mux(isBF16, Cat(0.U(4.W), bf16RawA0.sig), Cat(0.U(1.W), fp16RawA0.sig))
  sharedMul.a1 := Mux(isBF16, Cat(0.U(4.W), bf16RawA1.sig), Cat(0.U(1.W), fp16RawA1.sig))
  sharedMul.b0 := Mux(isBF16, Cat(0.U(16.W), bf16RawB0.sig), Cat(0.U(13.W), fp16RawB0.sig))
  sharedMul.b1 := Mux(isBF16, Cat(0.U(16.W), bf16RawB1.sig), Cat(0.U(13.W), fp16RawB1.sig))
  sharedMul.en := regEnable(1)

  fp16s20.io.in := S1Reg(fp16s10.io.out)
  fp16s21.io.in := S1Reg(fp16s11.io.out)
  fp16s20.io.prod := sharedMul.prod0(21, 0)
  fp16s21.io.prod := sharedMul.prod1(21, 0)
  fp16s30.io.in := S2Reg(fp16s20.io.out)
  fp16s31.io.in := S2Reg(fp16s21.io.out)

  bf16s20.io.in := S1Reg(bf16s10.io.out)
  bf16s21.io.in := S1Reg(bf16s11.io.out)
  bf16s20.io.prod := sharedMul.prod0(15, 0)
  bf16s21.io.prod := sharedMul.prod1(15, 0)
  bf16s30.io.in := S2Reg(bf16s20.io.out)
  bf16s31.io.in := S2Reg(bf16s21.io.out)

  private val s2IsBF16 = S2Reg(S1Reg(isBF16))
  private val fp16Result = Cat(fp16s31.io.result, fp16s30.io.result)
  private val bf16Result = Cat(bf16s31.io.result, bf16s30.io.result)
  private val fp16Flags = fp16s30.io.fflags | fp16s31.io.fflags
  private val bf16Flags = bf16s30.io.fflags | bf16s31.io.fflags

  io.out.bits.result := Mux(s2IsBF16, bf16Result, fp16Result)
  io.out.bits.fflags := Mux(s2IsBF16, bf16Flags, fp16Flags)
  io.out.bits.ctrl.foreach(_ := S2Reg(S1Reg(io.in.bits.ctrl.get)))

  private val c0 = S2Reg(S1Reg(lane0C))
  private val c1 = S2Reg(S1Reg(lane1C))
  private val rm2 = S2Reg(S1Reg(io.in.bits.rm))
  private val op2 = S2Reg(S1Reg(FN_FMADD(2, 0)))

  toAddFp16(0).mulOutput := fp16s30.io.to_fadd
  toAddFp16(1).mulOutput := fp16s31.io.to_fadd
  toAddFp16(0).addAnother := c0
  toAddFp16(1).addAnother := c1
  toAddFp16(0).op := op2
  toAddFp16(1).op := op2
  toAddFp16(0).rm := rm2
  toAddFp16(1).rm := rm2
  toAddFp16(0).ctrl.foreach(_ := S2Reg(S1Reg(io.in.bits.ctrl.get)))
  toAddFp16(1).ctrl.foreach(_ := S2Reg(S1Reg(io.in.bits.ctrl.get)))

  toAddBf16(0).mulOutput := bf16s30.io.to_fadd
  toAddBf16(1).mulOutput := bf16s31.io.to_fadd
  toAddBf16(0).addAnother := c0
  toAddBf16(1).addAnother := c1
  toAddBf16(0).op := op2
  toAddBf16(1).op := op2
  toAddBf16(0).rm := rm2
  toAddBf16(1).rm := rm2
  toAddBf16(0).ctrl.foreach(_ := S2Reg(S1Reg(io.in.bits.ctrl.get)))
  toAddBf16(1).ctrl.foreach(_ := S2Reg(S1Reg(io.in.bits.ctrl.get)))
}

