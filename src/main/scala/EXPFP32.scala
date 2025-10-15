import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

import fudian.{FCMA_ADD_s1, FCMA_ADD_s2, FMUL_s1, FMUL_s2, FMUL_s3, FMULToFADD, RawFloat}
import fudian.utils.Multiplier

object EXPFP32Parameters {
  val LOG2E     = "h3FB8AA3B".U(32.W)
  val C0        = "h3F800000".U(32.W)
  val C1        = "h3F317218".U(32.W)
  val C2        = "h3E75FDF0".U(32.W)
  val MIN_INPUT = "hC2AE999A".U(32.W) // -87.3
  val MAX_INPUT = "h42B16666".U(32.W) // +88.7
  val ZERO      = 0.U(32.W)
  val INF       = "h7F800000".U(32.W)
  val NAN       = "h7FC00000".U(32.W)
}

class MULFP32Input extends Bundle {
  val in1 = UInt(32.W)
  val in2 = UInt(32.W)
  val rm  = UInt(3.W)
}
class MULFP32Output extends Bundle {
  val out    = UInt(32.W)
  val toAdd  = new FMULToFADD(8, 24)
  val fflags = UInt(5.W)
}
class MULFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Input(new MULFP32Input)
    val out = Output(new MULFP32Output)
  })
  val expWidth = 8
  val precision = 24

  val mul   = Module(new Multiplier(precision + 1, pipeAt = Seq()))
  val mulS1 = Module(new FMUL_s1(expWidth, precision))
  val mulS2 = Module(new FMUL_s2(expWidth, precision))
  val mulS3 = Module(new FMUL_s3(expWidth, precision))

  mulS1.io.a  := io.in.in1
  mulS1.io.b  := io.in.in2
  mulS1.io.rm := io.in.rm

  val rawA = RawFloat.fromUInt(io.in.in1, expWidth, precision)
  val rawB = RawFloat.fromUInt(io.in.in2, expWidth, precision)

  mul.io.a := rawA.sig
  mul.io.b := rawB.sig
  mul.io.regEnables.foreach(_ := true.B)

  val s1Out  = RegNext(mulS1.io.out)
  val prod   = RegNext(mul.io.result)

  mulS2.io.in   := s1Out
  mulS2.io.prod := prod

  val s2Reg = RegNext(mulS2.io.out)

  mulS3.io.in := s2Reg

  val resultReg = RegNext(mulS3.io.result)
  val fflagsReg = RegNext(mulS3.io.fflags)
  val toAddReg  = RegNext(mulS3.io.to_fadd)

  io.out.out    := resultReg
  io.out.toAdd  := toAddReg
  io.out.fflags := fflagsReg
}

class CMAFP32Input extends Bundle {
  val a  = UInt(32.W)
  val b  = UInt(32.W)
  val c  = UInt(32.W)
  val rm = UInt(3.W)
}
class CMAFP32Output extends Bundle {
  val out    = UInt(32.W)
  val fflags = UInt(5.W)
}
class CMAFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Input(new CMAFP32Input)
    val out = Output(new CMAFP32Output)
  })
  val expWidth = 8
  val precision = 24

  val mul   = Module(new MULFP32)
  val addS1 = Module(new FCMA_ADD_s1(expWidth, precision * 2, precision))
  val addS2 = Module(new FCMA_ADD_s2(expWidth, precision * 2, precision))

  mul.io.in.in1 := io.in.a
  mul.io.in.in2 := io.in.b
  mul.io.in.rm  := io.in.rm

  val toAdd = mul.io.out.toAdd
  val cReg  = ShiftRegister(io.in.c, 3, true.B)
  val rmReg = ShiftRegister(io.in.rm, 3, true.B)

  addS1.io.a             := Cat(cReg, 0.U(precision.W))
  addS1.io.b             := toAdd.fp_prod.asUInt
  addS1.io.rm            := rmReg
  addS1.io.b_inter_valid := true.B
  addS1.io.b_inter_flags := toAdd.inter_flags

  val s1Reg = RegNext(addS1.io.out)
  addS2.io.in := s1Reg

  val resultReg = RegNext(addS2.io.result)
  val fflagsReg = RegNext(addS2.io.fflags)

  io.out.out    := resultReg
  io.out.fflags := fflagsReg
}

class DecomposeFP32Input extends Bundle {
  val y = UInt(32.W)
}
class DecomposeFP32Output extends Bundle {
  val yi  = UInt(9.W)   // 符号 + 整数部分
  val yfi = UInt(8.W)   // 符号 + 小数高7位（拼接）
  val yfj = UInt(32.W)  // 小数低16位转浮点数
}
class DecomposeFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Input(new DecomposeFP32Input)
    val out = Output(new DecomposeFP32Output)
  })

  val raw = io.in.y
  val s = raw(31)
  val e = raw(30,23)
  val f = raw(22,0)

  val man = Cat(1.U(1.W), f)
  val eS  = (e.zext - 127.S).asSInt
  val sh  = Mux(eS < 0.S, (-eS).asUInt, eS.asUInt)
  val shifted = Mux(eS < 0.S, man.zext.asUInt >> sh, man.zext.asUInt << sh)

  val fracLow16 = shifted(15,0)

  val isZero = fracLow16 === 0.U
  val clz    = PriorityEncoder(Reverse(fracLow16))
  val exp    = Mux(isZero, 0.U(8.W), (119.U(8.W) - clz))

  val mant   = (fracLow16 << (clz + 8.U))(22,0)

  val yi  = Cat(s, shifted(30,23))
  val yfi = Cat(s, shifted(22,16))
  val yfj = Cat(s, exp, mant)

  val yiReg  = RegNext(yi)
  val yfiReg = RegNext(yfi)
  val yfjReg = RegNext(yfj)

  io.out.yi  := yiReg
  io.out.yfi := yfiReg
  io.out.yfj := yfjReg
}

class EXPFP32LUTInput extends Bundle {
  val idx = UInt(8.W)
}
class EXPFP32LUTOutput extends Bundle {
  val value = UInt(32.W)
}

class EXPFP32LUT extends Module {
  val io = IO(new Bundle {
    val in = Input(new EXPFP32LUTInput)
    val out = Output(new EXPFP32LUTOutput)
  })

  val table = VecInit((0 until 256).map { i =>
    val sign = (i >> 7) & 1
    val mag  = i & 0x7f
    val x = if (sign == 1) -(mag.toDouble / 128.0) else (mag.toDouble / 128.0)
    val y = Math.pow(2.0, x)
    val bits = java.lang.Float.floatToIntBits(y.toFloat)
    bits.U(32.W)
  })

  val valueReg = RegNext(table(io.in.idx))

  io.out.value := valueReg
}


class FilterFP32Input extends Bundle {
  val in = UInt(32.W)
  val rm = UInt(3.W)
}
class FilterFP32Output extends Bundle {
  val out       = UInt(32.W)
  val bypassVal = UInt(32.W)
  val bypass     = Bool()
}
class FilterFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Input(new FilterFP32Input)
    val out = Output(new FilterFP32Output)
  })

  val s = io.in.in(31)
  val e = io.in.in(30, 23)
  val f = io.in.in(22, 0)

  val isInfPos = (e === "hFF".U) && (f === 0.U) && (s === 0.U)
  val isInfNeg = (e === "hFF".U) && (f === 0.U) && (s === 1.U)
  val isNaN    = (e === "hFF".U) && (f =/= 0.U)

  val tooBig = (!s) && (io.in.in > EXPFP32Parameters.MAX_INPUT) // +88.7
  val tooNeg = s && (io.in.in > EXPFP32Parameters.MIN_INPUT)    // -87.3

  val bypass = isNaN || isInfPos || isInfNeg || tooBig || tooNeg

  val filteredVal = Wire(UInt(32.W))
  val bypassVal   = Wire(UInt(32.W))

  when (isNaN) {
    filteredVal := EXPFP32Parameters.NAN
    bypassVal   := EXPFP32Parameters.NAN
  }.elsewhen (isInfPos || tooBig) {
    filteredVal := EXPFP32Parameters.INF
    bypassVal   := EXPFP32Parameters.INF
  }.elsewhen (isInfNeg || tooNeg) {
    filteredVal := EXPFP32Parameters.ZERO
    bypassVal   := EXPFP32Parameters.ZERO
  }.otherwise {
    filteredVal := io.in.in
    bypassVal   := EXPFP32Parameters.ZERO
  }

  val filteredValReg = RegNext(filteredVal)
  val bypassValReg   = RegNext(bypassVal)
  val bypassReg      = RegNext(bypass)

  io.out.out       := filteredValReg
  io.out.bypassVal := bypassValReg
  io.out.bypass    := bypassReg
}

class EXPFP32MainPathInput extends Bundle {
  val in = UInt(32.W)
  val rm = UInt(3.W)
}
class EXPFP32MainPathOutput extends Bundle {
  val out = UInt(32.W)
}

class EXPFP32MainPath extends Module {
  val io = IO(new Bundle {
    val in  = Input(new EXPFP32Input)
    val out = Output(new EXPFP32Output)
  })

  val mulA   = Module(new MULFP32)
  val cma0   = Module(new CMAFP32)
  val cma1   = Module(new CMAFP32)
  val mulC   = Module(new MULFP32)
  val decomp = Module(new DecomposeFP32)
  val lut    = Module(new EXPFP32LUT)

  val rm = io.in.rm
  val rmForCma0 = ShiftRegister(rm, 4, true.B)
  val rmForCma1 = ShiftRegister(rm, 9, true.B)
  val rmForMulC = ShiftRegister(rm, 14, true.B)

  mulA.io.in.in1 := io.in.in
  mulA.io.in.in2 := EXPFP32Parameters.LOG2E
  mulA.io.in.rm  := rm

  val y = mulA.io.out.out

  decomp.io.in.y := y
  val yi  = decomp.io.out.yi
  val yfi = decomp.io.out.yfi
  val yfj = decomp.io.out.yfj

  cma0.io.in.a  := yfj
  cma0.io.in.b  := EXPFP32Parameters.C2
  cma0.io.in.c  := EXPFP32Parameters.C1
  cma0.io.in.rm := rmForCma0

  val yfjReg = ShiftRegister(yfj, 5, true.B)
  cma1.io.in.a  := yfjReg
  cma1.io.in.b  := cma0.io.out.out
  cma1.io.in.c  := EXPFP32Parameters.C0
  cma1.io.in.rm := rmForCma1

  val yfiReg = ShiftRegister(yfi, 9, true.B)
  lut.io.in.idx  := yfiReg
  mulC.io.in.in1 := lut.io.out.value
  mulC.io.in.in2 := cma1.io.out.out
  mulC.io.in.rm  := rmForMulC

  val yiReg = ShiftRegister(yi, 13, true.B)
  val ef    = mulC.io.out.out(30,23)
  val mant  = mulC.io.out.out(22,0)
  val sign  = yiReg(8)
  val ei    = yiReg(7,0)
  val e = (ef.zext.asSInt + Mux(sign === 1.U, -ei.zext.asSInt, ei.zext.asSInt))(7, 0)

  val out  = Cat(0.U(1.W), e(7, 0), mant)
  val outReg = RegNext(out)

  io.out.out := outReg
}

class EXPFP32Input extends Bundle {
  val in = UInt(32.W)
  val rm = UInt(3.W)
}
class EXPFP32Output extends Bundle {
  val out = UInt(32.W)
}

class EXPFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new EXPFP32Input))
    val out = Decoupled(new EXPFP32Output)
  })

  val entries = 24
  val latency = 19

  val filter   = Module(new FilterFP32)
  val mainPath = Module(new EXPFP32MainPath)
  val outQ     = Module(new Queue(new EXPFP32Output, entries, pipe = true, flow = false))

  filter.io.in.in := io.in.bits.in
  filter.io.in.rm := io.in.bits.rm

  mainPath.io.in.in := filter.io.out.out
  mainPath.io.in.rm := io.in.bits.rm

  val validPipe   = ShiftRegister(io.in.fire, latency, true.B)
  val bypassPipe  = ShiftRegister(filter.io.out.bypass, latency - 1, true.B)
  val bypassValP  = ShiftRegister(filter.io.out.bypassVal, latency - 1, true.B)

  val mainOut = mainPath.io.out.out
  val finalOut = Mux(bypassPipe, bypassValP, mainOut)

  outQ.io.enq.valid    := validPipe
  outQ.io.enq.bits.out := finalOut

  val inflight = RegInit(0.U(6.W))
  inflight := inflight + io.in.fire - outQ.io.enq.fire

  val freeSlots = (entries.U - outQ.io.count) - inflight
  io.in.ready := freeSlots > 0.U

  io.out <> outQ.io.deq
}

object EXPFP32Gen extends App {
  ChiselStage.emitSystemVerilogFile(
    new MULFP32,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
  ChiselStage.emitSystemVerilogFile(
    new CMAFP32,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
  ChiselStage.emitSystemVerilogFile(
    new EXPFP32MainPath,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
  ChiselStage.emitSystemVerilogFile(
    new EXPFP32,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
}
