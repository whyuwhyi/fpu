package FPUv2

import FPUv2.utils._
import chisel3.experimental.dataview._
import chisel3.{VecInit, _}
import chisel3.util._

class ScalarFPU(expWidth: Int, precision: Int, ctrlGen: Data = EmptyFPUCtrl()) extends Module {
  val len = expWidth + precision
  val io = IO(new Bundle {
    val in = Flipped(DecoupledIO(new FPUInput(len, ctrlGen, true)))
    val out = DecoupledIO(new FPUOutput(64, ctrlGen))
    val select = Output(UInt(3.W))
  })
  val fmaModule = Module(new FMA(expWidth, precision, ctrlGen, useExternalSharedMul = true))
  val cmpModule = Module(new FCMP(expWidth, precision, ctrlGen))
  val mvModule = Module(new FPMV(expWidth, precision, ctrlGen))
  val fpToIntModule = Module(new FPToInt(ctrlGen))
  val intToFpModule = Module(new IntToFP(ctrlGen))
  val cvtModule = Module(new FPToFP_cvt(ctrlGen))
  val vecCvtModule = Module(new FPToFP_cvt(ctrlGen))
  val subModules = Seq[FPUSubModule](
    fmaModule,
    cmpModule,
    mvModule,
    fpToIntModule,
    intToFpModule,
    cvtModule,
    vecCvtModule
  )

  val extOp = io.in.bits.op
  val packedOp = CustomLaneOps.isPacked(extOp)
  val vecCvtOp = CustomLaneOps.isVectorConvert(extOp)
  val fu = Mux(packedOp, 6.U(3.W), Mux(vecCvtOp, 7.U(3.W), extOp.head(3)))
  val localFmaOp = Mux(packedOp, CustomLaneOps.packedSubOp(extOp), extOp(2, 0))
  val localVecCvtOp = Mux(vecCvtOp, CustomLaneOps.cvtSubOp(extOp), extOp(2, 0))
  val toFma = fu === 0.U || fu === 6.U
  fmaModule.packedMode := packedOp

  val sharedMul = Module(new DualSlicedMantissaMul(Seq(1)))
  sharedMul.io.a0 := fmaModule.sharedMul.a0
  sharedMul.io.b0 := fmaModule.sharedMul.b0
  sharedMul.io.a1 := fmaModule.sharedMul.a1
  sharedMul.io.b1 := fmaModule.sharedMul.b1
  sharedMul.io.regEnables(0) := fmaModule.sharedMul.en
  fmaModule.sharedMul.prod0 := sharedMul.io.prod0
  fmaModule.sharedMul.prod1 := sharedMul.io.prod1

  fmaModule.io.in.bits.op := Mux(toFma, localFmaOp, 0.U(3.W))
  fmaModule.io.in.bits.rm := Mux(toFma, io.in.bits.rm, 0.U(3.W))
  fmaModule.io.in.bits.a := Mux(toFma, io.in.bits.a, 0.U(len.W))
  fmaModule.io.in.bits.b := Mux(toFma, io.in.bits.b, 0.U(len.W))
  fmaModule.io.in.bits.c := Mux(toFma, io.in.bits.c, 0.U(len.W))
  fmaModule.io.in.bits.ctrl.foreach(_ := Mux(toFma, io.in.bits.ctrl.get, 0.U.asTypeOf(io.in.bits.ctrl.get)))
  fmaModule.io.in.valid := toFma && io.in.valid

  val routedModules = Seq(
    (1.U, cmpModule),
    (2.U, mvModule),
    (3.U, fpToIntModule),
    (4.U, intToFpModule),
    (5.U, cvtModule),
    (7.U, vecCvtModule)
  )
  routedModules.foreach { case (tag, module) =>
    module.io.in.bits.op := Mux(fu === tag, Mux(tag === 7.U, localVecCvtOp, extOp(2, 0)), 0.U(3.W))
    module.io.in.bits.rm := Mux(fu === tag, io.in.bits.rm, 0.U(3.W))
    module.io.in.bits.a := Mux(fu === tag, io.in.bits.a, 0.U(len.W))
    module.io.in.bits.b := Mux(fu === tag, io.in.bits.b, 0.U(len.W))
    module.io.in.bits.c := Mux(fu === tag, io.in.bits.c, 0.U(len.W))
    module.io.in.bits.ctrl.foreach(_ := Mux(fu === tag, io.in.bits.ctrl.get, 0.U.asTypeOf(io.in.bits.ctrl.get)))
    module.io.in.valid := fu === tag && io.in.valid
  }
  io.in.ready := MuxLookup(fu, false.B)(Seq(
    0.U -> fmaModule.io.in.ready,
    1.U -> cmpModule.io.in.ready,
    2.U -> mvModule.io.in.ready,
    3.U -> fpToIntModule.io.in.ready,
    4.U -> intToFpModule.io.in.ready,
    5.U -> cvtModule.io.in.ready,
    6.U -> fmaModule.io.in.ready,
    7.U -> vecCvtModule.io.in.ready
  ))

  val outArbiter = Module(new Arbiter(new FPUOutput(64, ctrlGen), subModules.length))
  subModules.zipWithIndex.foreach{ case (module, idx) =>
    outArbiter.io.in(idx) <> module.io.out
  }
  io.out <> outArbiter.io.out
  io.select := MuxLookup(outArbiter.io.chosen, 0.U(3.W))(Seq(
    0.U -> 0.U(3.W),
    1.U -> 1.U(3.W),
    2.U -> 2.U(3.W),
    3.U -> 3.U(3.W),
    4.U -> 4.U(3.W),
    5.U -> 5.U(3.W),
    6.U -> 7.U(3.W)
  ))
}

class VectorFPU[T <: TestFPUCtrl](expWidth: Int, precision: Int, softThread: Int = 32, hardThread: Int = 32, ctrlGen:T)
  extends Module {
  assert(softThread % hardThread == 0)
  assert(softThread>2 && hardThread>1)

  val len = expWidth + precision
  val io = IO(new Bundle {
    val in = Flipped(DecoupledIO(new vecFPUInput(softThread, len, ctrlGen)))
    val out = DecoupledIO(new Bundle {
      val data = Vec(softThread, new FPUOutput(64, EmptyFPUCtrl()))
      val ctrl = ctrlGen.cloneType
    })
  })

  val FPUArray = Seq(Module(new ScalarFPU(expWidth, precision, ctrlGen))) ++
                  Seq.fill(hardThread-1)(Module(new ScalarFPU(expWidth, precision, EmptyFPUCtrl())))

  if(softThread == hardThread){
    io.in.ready := FPUArray(0).io.in.ready
    FPUArray.zipWithIndex.foreach{ case (x, i) =>
      x.io.in.valid := io.in.valid
      x.io.in.bits.viewAsSupertype(new FPUInput(len, EmptyFPUCtrl(), true)) := io.in.bits.data(i)
      x.io.in.bits.ctrl.foreach( _ := io.in.bits.ctrl )
    }

    io.out.valid := FPUArray(0).io.out.valid
    io.out.bits.ctrl := FPUArray(0).io.out.bits.ctrl.get
    FPUArray.zipWithIndex.foreach{ case (fpu, i) =>
      fpu.io.out.ready := io.out.ready
      io.out.bits.data(i).viewAsSupertype(new FPUOutput(64, EmptyFPUCtrl())) := fpu.io.out.bits
    }
  }
  else {
    //================ Result Sending ========
    val maxIter = softThread / hardThread
    //val maskSlice = (1<<hardThread-1).U(softThread.W)
    val inReg = RegInit(VecInit.fill(softThread)(0.U.asTypeOf(new FPUInput(len, EmptyFPUCtrl(), true))))
    val inCtrlReg = RegInit(0.U.asTypeOf(ctrlGen))

    //val sendCS = RegInit(0.U(log2Ceil(maxIter+1).W))
    val sendNS = WireInit(0.U(log2Ceil(maxIter+1).W))

    // process #1
    val sendCS = RegNext(sendNS)
    // process #2
    switch(sendCS){
      is(0.U){
        when(io.in.fire){
          sendNS := sendCS +% 1.U
        }.otherwise{ sendNS := 0.U }
      }
      is((1 until maxIter).map{_.U}){
        when(FPUArray(0).io.in.fire){
          sendNS := sendCS +% 1.U
        }.otherwise{sendNS := sendCS}
      }
      is(maxIter.U){
        when(FPUArray(0).io.in.fire){
          when(io.in.fire){
            sendNS := 1.U
          }.otherwise{
            sendNS := 0.U
          }
        }.otherwise{sendNS := sendCS}
      }
    }
    // process #3A
    switch(sendNS){
      is(0.U){
        inReg.foreach{ _ := 0.U.asTypeOf(inReg(0))}
        inCtrlReg := 0.U.asTypeOf(ctrlGen)
      }
      is(1.U){
        when(io.in.fire){
          (inReg zip io.in.bits.data).foreach { x => x._1 := x._2 }
          inCtrlReg := io.in.bits.ctrl
        }.otherwise{}
      }
      is((2 to maxIter).map(_.U)){
        when(FPUArray(0).io.in.fire){
          (0 until softThread).foreach{
            i => inReg(i) := {
              if (i + hardThread < softThread)
                inReg(i + hardThread)
              else
                0.U.asTypeOf(inReg(i))
            }
          }
        }.otherwise{

        }
      }
    }
    // process #3B
    FPUArray(0).io.in.bits.viewAsSupertype(new FPUInput(len, EmptyFPUCtrl(), true)) := inReg(0)
    FPUArray(0).io.in.bits.ctrl.foreach( _ := inCtrlReg)
    FPUArray(0).io.in.valid := sendCS =/= 0.U
    (1 until hardThread).foreach{ i =>
      FPUArray(i).io.in.bits := inReg(i)
      FPUArray(i).io.in.valid := sendCS =/= 0.U
    }
    io.in.ready := sendCS===0.U || sendCS===maxIter.U && FPUArray(0).io.in.ready
    //=============== Result Collecting ===================
    //val recvCS = RegInit(0.U(log2Ceil(maxIter+1).W))
    val recvNS = WireInit(0.U(log2Ceil(maxIter+1).W))
    val recvCS = RegNext(recvNS)
    val outReg = RegInit(VecInit.fill(softThread)(0.U.asTypeOf(new FPUOutput(len, EmptyFPUCtrl()))))
    val outCtrlReg = RegInit(0.U.asTypeOf(ctrlGen))

    switch(recvCS){
      is(maxIter.U){
        when(io.out.fire){
          when(FPUArray(0).io.out.fire){
            recvNS := 1.U
          }.otherwise{
            recvNS := 0.U
          }
        }.otherwise{ recvNS := recvCS }
      }
      is((0 until maxIter).map(_.U)){
        when(FPUArray(0).io.out.fire){
          recvNS := recvCS +% 1.U
        }.otherwise{ recvNS := recvCS }
      }
    }

    switch(recvNS){
      is(0.U){
        outReg.foreach{ _ := 0.U.asTypeOf(outReg(0)) }
        outCtrlReg := 0.U.asTypeOf(outCtrlReg)
      }
      is((1 to maxIter).map(_.U)){
        when(FPUArray(0).io.out.fire){
          (0 until softThread).foreach{ i =>
            outReg(i) := {
              if(i+hardThread<softThread)
                outReg(i + hardThread)
              else
                FPUArray(i+hardThread-softThread).io.out.bits
            }
          }
          outCtrlReg := Mux(recvNS===1.U, FPUArray(0).io.out.bits.ctrl.get, outCtrlReg)
        }.otherwise{}
      }
    }
    io.out.valid := recvCS === maxIter.U
    io.out.bits.ctrl := outCtrlReg
//    (io.out.bits.data zip FPUArray).foreach{ case (data, fpu) =>
//      data := fpu.io.out.bits
//      fpu.io.out.ready := io.out.ready
//    }
    (io.out.bits.data zip outReg).foreach{ case(data, reg) => data := reg }
    FPUArray.foreach( _.io.out.ready := io.out.ready || recvCS =/= maxIter.U)
  }
}