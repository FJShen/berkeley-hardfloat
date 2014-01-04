package hardfloat

import Chisel._
import Node._
import scala.sys.process._

class FMA(sigWidth: Int, expWidth: Int)  extends Module {
  val io = new Bundle {
    val a = Bits(INPUT, sigWidth + expWidth)
    val b = Bits(INPUT, sigWidth + expWidth)
    val c = Bits(INPUT, sigWidth + expWidth)
    val out = Bits(OUTPUT, sigWidth + expWidth)
  }

  val fma = Module(new mulAddSubRecodedFloatN(sigWidth, expWidth))
  fma.io.op := UInt(0)
  fma.io.a := floatNToRecodedFloatN(io.a, sigWidth, expWidth)
  fma.io.b := floatNToRecodedFloatN(io.b, sigWidth, expWidth)
  fma.io.c := floatNToRecodedFloatN(io.c, sigWidth, expWidth)
  fma.io.roundingMode := UInt(0)
  io.out := recodedFloatNToFloatN(fma.io.out, sigWidth, expWidth)
}

class FMAPipeline(sigWidth: Int, expWidth: Int) extends Module {
  val io = new Bundle {
    val req = Bool(INPUT)
    val resp = Bool(OUTPUT)
    val a = Bits(INPUT, sigWidth + expWidth + 1)
    val b = Bits(INPUT, sigWidth + expWidth + 1)
    val c = Bits(INPUT, sigWidth + expWidth + 1)
    val out = Bits(OUTPUT, sigWidth + expWidth + 1 + 5)
  }
  val fma = Module(new mulAddSubRecodedFloatN(sigWidth, expWidth))
  fma.io.a := io.a
  fma.io.b := io.b
  fma.io.c := io.c
  fma.io.op := Bits(0)
  fma.io.roundingMode := io.a
  io.out := RegEnable(Cat(fma.io.exceptionFlags, fma.io.out), io.req)
  io.resp := Reg(next=io.req)
}

class FMARecoded(sigWidth: Int, expWidth: Int)  extends Module {
  val io = new Bundle {
    val a = Bits(INPUT, sigWidth + expWidth + 1)
    val b = Bits(INPUT, sigWidth + expWidth + 1)
    val c = Bits(INPUT, sigWidth + expWidth + 1)
    val out = Bits(OUTPUT, sigWidth + expWidth + 1 + 5)
  }

  val fma = Module(new FMAPipeline(sigWidth, expWidth))
  val en = io.a.orR
  fma.io.req := Reg(next=en)
  fma.io.a := RegEnable(io.a, en)
  fma.io.b := RegEnable(io.b, en)
  fma.io.c := RegEnable(io.c, en)
  io.out := RegEnable(fma.io.out, fma.io.resp)
}

class FMATests(c: FMA, s: Int) extends Tester(c, Array(c.io)) {
  require(s == 32 || s == 64)
  val vars = new collection.mutable.HashMap[Node, Node]()

  def i2d(x: BigInt) =
    if (s == 32) java.lang.Float.intBitsToFloat(x.toInt)
    else java.lang.Double.longBitsToDouble(x.toLong)

  def canonicalize(x: BigInt) =
    if (i2d(x).isNaN) (BigInt(1) << s) - 1
    else x

  var ok: Boolean = true

  def testOne(s: String) = if (ok) {
    val t = s.split(' ')
    vars(c.io.a) = Bits(BigInt(t(0), 16))
    vars(c.io.b) = Bits(BigInt(t(1), 16))
    vars(c.io.c) = Bits(BigInt(t(2), 16))
    vars(c.io.out) = Bits(canonicalize(BigInt(t(3), 16)))
    ok = step(vars)
  }

  def testAll: Boolean = {
    val logger = ProcessLogger(testOne, _ => ())
    Seq("bash", "-c", "testfloat_gen -rnear_even -n 6133248 f"+s+"_mulAdd | head -n10000") ! logger
    ok
  }
  defTests {
    testAll
  }
}

class FMAEnergy(comp: FMARecoded, s: Int) extends Tester(comp, Array(comp.io)) {
  require(s == 32 || s == 64)
  val expWidth = if (s == 32) 9 else 12
  val sigWidth = s - expWidth
  val vars = new collection.mutable.HashMap[Node, Node]()

  def x(n: BigInt, hi: Int, lo: Int): BigInt = (n >> lo) & ((1L << (hi-lo+1))-1)
  def x(n: BigInt, bit: Int): BigInt = x(n, bit, bit)

  def normalize(in: BigInt, width: Int) = {
    var log = 0
    for (i <- 1 until width)
      if (((in >> i) & 1) == 1)
        log = i
    val shift = x(~log, log2Up(width)-1, 0).toInt
    (in << shift, shift)
  }
  def recode(in: BigInt) = {
    val sign = x(in, sigWidth+expWidth-1)
    val expIn = x(in, sigWidth+expWidth-2, sigWidth)
    val fractIn = x(in, sigWidth-1, 0)
    val normWidth = 1 << log2Up(sigWidth)

    val isZeroExpIn = expIn == 0
    val isZeroFractIn = fractIn == 0
    val isZero = isZeroExpIn && isZeroFractIn
    val isSubnormal = isZeroExpIn && !isZeroFractIn

    val (norm, normCount) = normalize(fractIn << (normWidth-sigWidth), normWidth)
    val subnormal_expOut = x(-1, expWidth-1, log2Up(sigWidth)) | x(~normCount, log2Up(sigWidth)-1, 0)

    val normalizedFract = x(norm, normWidth-2, normWidth-sigWidth-1)
    val commonExp = if (isZeroExpIn) (if (isZeroFractIn) BigInt(0) else subnormal_expOut) else expIn
    val expAdjust = (if (isZero) 0 else 1 << expWidth-2) | (if (isSubnormal) 2 else 1)
    val adjustedCommonExp = commonExp + expAdjust
    val isNaN = x(adjustedCommonExp, expWidth-1, expWidth-2) == 3 && !isZeroFractIn

    val expOut = x(adjustedCommonExp | ((if (isNaN) 1L else 0L) << (expWidth-3)), expWidth-1, 0)
    val fractOut = if (isZeroExpIn) normalizedFract else fractIn

    println("recode " + in + " " + ((sign << (expWidth+sigWidth)) | (expOut << sigWidth) | fractOut))
    (sign << (expWidth+sigWidth)) | (expOut << sigWidth) | fractOut
  }

  def decode(in: BigInt) = {
    val sign = x(in, sigWidth+expWidth)
    val expIn = x(in, sigWidth+expWidth-1, sigWidth)
    val fractIn = x(in, sigWidth-1, 0)

    val isHighSubnormalIn = x(expIn, expWidth-3, 0) < 2
    val isSubnormal = x(expIn, expWidth-1, expWidth-3) == 1 || x(expIn, expWidth-1, expWidth-2) == 1 && isHighSubnormalIn
    val isNormal = x(expIn, expWidth-1, expWidth-2) == 1 && !isHighSubnormalIn || x(expIn, expWidth-1, expWidth-2) == 2
    val isSpecial = x(expIn, expWidth-1, expWidth-2) == 3
    val isNaN = isSpecial && x(expIn, expWidth-3) == 1

    val denormShiftDist = x(2 - x(expIn, log2Up(sigWidth)-1, 0), log2Up(sigWidth)-1, 0).toInt
    val subnormal_fractOut = x(((1L << sigWidth) | fractIn) >> denormShiftDist, sigWidth-1, 0)
    val normal_expOut = x(expIn - ((1 << (expWidth-2))+1), expWidth-2, 0)

    val expOut = if (isNormal) normal_expOut else if (isSpecial) x(-1, expWidth-2, 0) else BigInt(0)
    val fractOut = if (isNormal || isNaN) fractIn else if (isSubnormal) subnormal_fractOut else BigInt(0)

    (sign << (sigWidth+expWidth-1)) | (expOut << sigWidth) | fractOut
  }

  def testOne(a: BigInt, b: BigInt, c: BigInt, y: BigInt) = {
    vars(comp.io.a) = Bits(a)
    vars(comp.io.b) = Bits(b)
    vars(comp.io.c) = Bits(c)
    vars(comp.io.out) = Bits(y)
    step(vars)
  }

  def testAll: Boolean = {
    for (i <- 0 until 10000)
      testOne(Rand.nextLong, Rand.nextLong, Rand.nextLong, 0)
    true
  }
  defTests {
    testAll
  }
}

object Rand {
  val fractZeros = 0.7
  def nextLong: Long = {
    var x = 0L
    for (i <- 0 until 64)
      x = (x << 1) | (if (util.Random.nextDouble > fractZeros) 1 else 0)
    x
  }
}

object FMATest {
  def main(args: Array[String]): Unit = {
    //chiselMainTest(args ++ Array("--compile", "--test",  "--genHarness"),
    //               () => Module(new FMA(23, 9))) { c => new FMATests(c, 32) }
    chiselMainTest(args ++ Array("--compile", "--test",  "--genHarness"),
                   () => Module(new FMA(52, 12))) { c => new FMATests(c, 64) }
    //chiselMainTest(args ++ Array("--compile", "--test",  "--genHarness"),
    //               () => Module(new FMARecoded(52, 12))) { c => new FMAEnergy(c, 64) }
  }
}