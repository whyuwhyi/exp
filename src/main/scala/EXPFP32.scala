import chisel3._
import circt.stage.ChiselStage
import chisel3.util._

object EXPFP32Parameters {
  val LOG2E = "h3FB8AA3B".U(32.W)
  val C0    = "h3F800000".U(32.W)
  val C1    = "h3F317218".U(32.W)
  val C2    = "h3E75FDF0".U(32.W)
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
  io.out.value := table(io.in.idx)
}

class ADDFP32Input extends Bundle {
  val in1 = UInt(32.W)
  val in2 = UInt(32.W)
}
class ADDFP32Output extends Bundle {
  val out = UInt(32.W)
}
class ADDFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ADDFP32Input))
    val out = Decoupled(new ADDFP32Output)
  })

  val s1_valid = RegInit(false.B)
  val s2_valid = RegInit(false.B)
  val s2_ready = io.out.ready
  val s1_ready = !s1_valid || s2_ready
  io.in.ready  := s1_ready
  io.out.valid := s2_valid
  val s1_fire = s1_ready && io.in.valid
  val s2_fire = s2_ready && s1_valid
  when(s1_ready){ s1_valid := io.in.valid }
  when(s2_ready){ s2_valid := s1_valid }

  val s2_sx       = Reg(UInt(1.W))
  val s2_sy       = Reg(UInt(1.W))
  val s2_ex       = Reg(UInt(8.W))
  val s2_fx       = Reg(UInt(25.W))
  val s2_aligned  = Reg(UInt(25.W))
  val s2_guard    = Reg(Bool())
  val s2_round    = Reg(Bool())
  val s2_sticky   = Reg(Bool())
  val s2_isSub    = Reg(Bool())

  when(s1_fire){
    val a = io.in.bits.in1
    val b = io.in.bits.in2
    val aExp  = a(30,23)
    val bExp  = b(30,23)
    val aFrac = Cat("b01".U(2.W), a(22,0))
    val bFrac = Cat("b01".U(2.W), b(22,0))
    val swap = WireDefault(false.B)
    when(aExp < bExp){ swap := true.B } .elsewhen(aExp === bExp){ swap := aFrac < bFrac }
    val x = Mux(swap,b,a)
    val y = Mux(swap,a,b)
    val sx = x(31)
    val sy = y(31)
    val ex = x(30,23)
    val ey = y(30,23)
    val fx = Cat("b01".U(2.W), x(22,0))
    val fy = Cat("b01".U(2.W), y(22,0))
    val diff = (ex.zext - ey.zext).asUInt
    val diffGe25 = diff > 24.U
    val aligned = Mux(diffGe25,0.U(25.W),(fy>>diff)(24,0))
    val guard = Mux(diff===0.U,false.B,Mux(diff>25.U,false.B,((fy>>(diff-1.U))(0)).asBool))
    val round = Mux(diff<=1.U,false.B,Mux(diff>26.U,false.B,((fy>>(diff-2.U))(0)).asBool))
    val stickyCount = Mux(diff<=2.U,0.U,Mux(diff-2.U>25.U,25.U,diff-2.U))
    val stickyfask = (1.U(26.W)<<stickyCount)-1.U
    val sticky = Mux(diff<=2.U,false.B,(fy&stickyfask(24,0)).orR)
    s2_sx      := sx
    s2_sy      := sy
    s2_ex      := ex
    s2_fx      := fx
    s2_aligned := aligned
    s2_guard   := guard
    s2_round   := round
    s2_sticky  := sticky
    s2_isSub   := sx ^ sy
  }

  val outReg = Reg(UInt(32.W))
  io.out.bits.out := outReg

  when(s2_fire){
    val sumAdd = Cat(0.U(1.W),s2_fx)+Cat(0.U(1.W),s2_aligned)
    val carryAdd = sumAdd(25)
    val sumSub = Cat(0.U(1.W),s2_fx)+Cat(0.U(1.W),(~s2_aligned))+1.U
    val subPre = sumSub(24,0)

    val expAdd = Wire(UInt(8.W))
    val mantAdd = Wire(UInt(25.W))
    val gA = WireDefault(false.B)
    val rA = WireDefault(false.B)
    val sA = WireDefault(false.B)
    when(carryAdd){
      expAdd := s2_ex + 1.U
      mantAdd := sumAdd(25,1)
      gA := sumAdd(0).asBool
      rA := s2_guard
      sA := s2_round || s2_sticky
    }.otherwise{
      expAdd := s2_ex
      mantAdd := sumAdd(24,0)
      gA := s2_guard
      rA := s2_round
      sA := s2_sticky
    }

    val roundUp = gA&&(rA||sA||mantAdd(0).asBool)
    val mantAddR = mantAdd+roundUp.asUInt
    val addOverflow = mantAddR(24)
    val expAddR = Mux(addOverflow,expAdd+1.U,expAdd)
    val mantAddFinal = Mux(addOverflow,(mantAddR>>1).asUInt,mantAddR)

    val mag24 = subPre(23,0)
    val zeroSub = mag24===0.U
    val lz = PriorityEncoder(Reverse(mag24))
    val sh = Mux(zeroSub,24.U,lz)
    val canSh = sh < s2_ex
    val expSub = Mux(zeroSub,0.U,Mux(canSh,s2_ex-sh,0.U))
    val mantSub = Mux(zeroSub,0.U(25.W),Mux(canSh,(subPre<<sh)(24,0),0.U(25.W)))

    val expOut = Mux(s2_isSub,expSub,expAddR)
    val mant25 = Mux(s2_isSub,mantSub,mantAddFinal)
    val fracOut = mant25(22,0)
    val signOut = Wire(UInt(1.W))
    when(s2_isSub){
      when(mantSub===0.U){ signOut:=0.U } .otherwise{ signOut:=Mux(s2_fx>=s2_aligned,s2_sx,s2_sy) }
    }.otherwise{ signOut:=s2_sx }
    outReg := Cat(signOut,expOut,fracOut)
  }
}

class MULFP32Input extends Bundle {
  val in1 = UInt(32.W)
  val in2 = UInt(32.W)
}
class MULFP32Output extends Bundle {
  val out = UInt(32.W)
}
class MULFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MULFP32Input))
    val out = Decoupled(new MULFP32Output)
  })

  val s1_valid = RegInit(false.B)
  val s2_valid = RegInit(false.B)
  val s3_valid = RegInit(false.B)
  val s3_ready = io.out.ready
  val s2_ready = !s2_valid || s3_ready
  val s1_ready = !s1_valid || s2_ready
  io.in.ready  := s1_ready
  io.out.valid := s3_valid

  val s1_fire = s1_ready && io.in.valid
  val s2_fire = s2_ready && s1_valid
  val s3_fire = s3_ready && s2_valid

  when(s1_ready) { s1_valid := io.in.valid }
  when(s2_ready) { s2_valid := s1_valid }
  when(s3_ready) { s3_valid := s2_valid }

  val s1_sign  = Reg(UInt(1.W))
  val s1_eSumS = Reg(SInt(10.W))
  val s1_aMan  = Reg(UInt(24.W))
  val s1_bMan  = Reg(UInt(24.W))

  val s1_prod_ll = Reg(UInt(24.W))
  val s1_prod_lh = Reg(UInt(24.W))
  val s1_prod_hl = Reg(UInt(24.W))
  val s1_prod_hh = Reg(UInt(24.W))

  when(s1_fire) {
    val a = io.in.bits.in1
    val b = io.in.bits.in2
    val aS = a(31); val aE = a(30,23); val aF = a(22,0)
    val bS = b(31); val bE = b(30,23); val bF = b(22,0)

    val aDen = aE === 0.U
    val bDen = bE === 0.U
    val aMan = Cat(Mux(aDen, 0.U(1.W), 1.U(1.W)), aF)
    val bMan = Cat(Mux(bDen, 0.U(1.W), 1.U(1.W)), bF)

    s1_sign  := aS ^ bS
    s1_eSumS := (aE.zext + bE.zext - 127.S).asSInt
    s1_aMan  := aMan
    s1_bMan  := bMan

    val aL = aMan(11, 0)
    val aH = aMan(23, 12)
    val bL = bMan(11, 0)
    val bH = bMan(23, 12)

    s1_prod_ll := aL * bL
    s1_prod_lh := aL * bH
    s1_prod_hl := aH * bL
    s1_prod_hh := aH * bH
  }

  val s2_sign  = Reg(UInt(1.W))
  val s2_eSumS = Reg(SInt(10.W))
  val s2_prod  = Reg(UInt(48.W))

  when(s2_fire) {
    s2_sign  := s1_sign
    s2_eSumS := s1_eSumS

    val mid_sum = s1_prod_lh +& s1_prod_hl
    val prod_full = (s1_prod_hh << 24) + (mid_sum << 12) + s1_prod_ll

    s2_prod := prod_full(47, 0)
  }

  val outReg = Reg(UInt(32.W))
  io.out.bits.out := outReg

  when(s3_fire) {
    val lead   = s2_prod(47)
    val norm   = Mux(lead, s2_prod, s2_prod << 1)
    val frac24 = norm(46,23)
    val gr     = norm(22)
    val st     = norm(21,0).orR
    val rnd    = gr && (st || frac24(0))
    val fracR25= Cat(0.U(1.W), frac24) + rnd
    val carry  = fracR25(24)
    val frac23 = Mux(carry, fracR25(24,2), fracR25(23,1))
    val eAdj   = s2_eSumS + Mux(lead, 1.S, 0.S) + carry.zext
    val eLo    = eAdj < 0.S
    val eHi    = eAdj > 255.S
    val eU     = Mux(eLo, 0.U, Mux(eHi, 255.U, eAdj.asUInt))(7,0)
    outReg := Cat(s2_sign, eU, frac23)
  }
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

  io.out.yi := Cat(s, shifted(30,23))

  io.out.yfi := Cat(s, shifted(22,16))

  val fracLow16 = shifted(15,0)

  val isZero = fracLow16 === 0.U
  val clz    = PriorityEncoder(Reverse(fracLow16))
  val exp    = Mux(isZero, 0.U(8.W), (119.U(8.W) - clz))

  val mant   = (fracLow16 << (clz + 8.U))(22,0)

  val yfjOut = Cat(s, exp, mant)

  io.out.yfj := yfjOut
}

class EXPFP32Input extends Bundle {
  val in = UInt(32.W)
}
class EXPFP32Output extends Bundle {
  val out = UInt(32.W)
}
class EXPFP32 extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new EXPFP32Input))
    val out = Decoupled(new EXPFP32Output)
  })

  val mulA   = Module(new MULFP32)       // s0: x * LOG2E
  val decomp = Module(new DecomposeFP32) // s1: decompose y -> yi, yfi, yfj
  val mulB0  = Module(new MULFP32)       // s2: yfj * C2
  val addB0  = Module(new ADDFP32)       // s3: t0 + C1
  val mulB1  = Module(new MULFP32)       // s4: t1 * yfj
  val addB1  = Module(new ADDFP32)       // s5: t2 + C0
  val mulC   = Module(new MULFP32)       // s6: LUT(yfi) * t3
  val lut    = Module(new EXPFP32LUT)

  io.in.ready         := mulA.io.in.ready

  mulA.io.in.valid    := io.in.valid
  mulA.io.in.bits.in1 := io.in.bits.in
  mulA.io.in.bits.in2 := EXPFP32Parameters.LOG2E

  val s0Q = Module(new Queue(UInt(32.W), 1, pipe=true, flow=false))
  s0Q.io.enq.valid  := mulA.io.out.valid
  s0Q.io.enq.bits   := mulA.io.out.bits.out
  mulA.io.out.ready := s0Q.io.enq.ready

  decomp.io.in.y    := s0Q.io.deq.bits
  val yi_s1  = decomp.io.out.yi
  val yfi_s1 = decomp.io.out.yfi
  val yfj_s1 = decomp.io.out.yfj

  val qYfj = Module(new Queue(UInt(32.W), 6))
  val qYfi = Module(new Queue(UInt(8.W),  11))
  val qYi  = Module(new Queue(UInt(9.W),  14))

  val s1_fire = s0Q.io.deq.valid && mulB0.io.in.ready && qYfj.io.enq.ready && qYfi.io.enq.ready && qYi.io.enq.ready

  s0Q.io.deq.ready := s1_fire

  mulB0.io.in.valid    := s1_fire
  mulB0.io.in.bits.in1 := yfj_s1
  mulB0.io.in.bits.in2 := EXPFP32Parameters.C2

  qYfj.io.enq.valid := s1_fire;
  qYfj.io.enq.bits  := yfj_s1
  qYfi.io.enq.valid := s1_fire;
  qYfi.io.enq.bits  := yfi_s1
  qYi .io.enq.valid := s1_fire;
  qYi .io.enq.bits  := yi_s1

  addB0.io.in.valid    := mulB0.io.out.valid
  addB0.io.in.bits.in1 := mulB0.io.out.bits.out
  addB0.io.in.bits.in2 := EXPFP32Parameters.C1
  mulB0.io.out.ready   := addB0.io.in.ready

  val s4_in_valid = addB0.io.out.valid && qYfj.io.deq.valid
  mulB1.io.in.valid    := s4_in_valid
  mulB1.io.in.bits.in1 := addB0.io.out.bits.out
  mulB1.io.in.bits.in2 := qYfj.io.deq.bits

  addB0.io.out.ready   := mulB1.io.in.ready && qYfj.io.deq.valid
  qYfj.io.deq.ready    := mulB1.io.in.fire

  addB1.io.in.valid    := mulB1.io.out.valid
  addB1.io.in.bits.in1 := mulB1.io.out.bits.out
  addB1.io.in.bits.in2 := EXPFP32Parameters.C0
  mulB1.io.out.ready   := addB1.io.in.ready

  val s6_in_valid = addB1.io.out.valid && qYfi.io.deq.valid
  lut.io.in.idx         := qYfi.io.deq.bits
  mulC.io.in.valid     := s6_in_valid
  mulC.io.in.bits.in1  := lut.io.out.value
  mulC.io.in.bits.in2  := addB1.io.out.bits.out

  addB1.io.out.ready   := mulC.io.in.ready && qYfi.io.deq.valid
  qYfi.io.deq.ready    := mulC.io.in.fire

  val t4Valid = mulC.io.out.valid
  val yiValid = qYi.io.deq.valid
  val s7Fire  = t4Valid && yiValid

  val prodC = mulC.io.out.bits.out
  val eprod = prodC(30,23)
  val yiC   = qYi.io.deq.bits
  val sign  = yiC(8)
  val ee    = yiC(7,0)

  val eAdjS = eprod.zext.asSInt + Mux(sign === 1.U, -ee.zext.asSInt, ee.zext.asSInt)
  val eU    = Mux(eAdjS < 0.S, 0.U, Mux(eAdjS > 255.S, 255.U, eAdjS.asUInt))(7,0)
  val outW  = Cat(0.U(1.W), eU, prodC(22,0))

  io.out.valid       := s7Fire
  io.out.bits.out    := outW
  mulC.io.out.ready  := io.out.ready && yiValid
  qYi.io.deq.ready   := io.out.ready && t4Valid
}

object EXPFP32Gen extends App {
  ChiselStage.emitSystemVerilogFile(
    new ADDFP32,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
  ChiselStage.emitSystemVerilogFile(
    new MULFP32,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
  ChiselStage.emitSystemVerilogFile(
    new EXPFP32,
    Array("--target-dir","rtl"),
    Array("-lowering-options=disallowLocalVariables")
  )
}
