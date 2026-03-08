package FPUv2

object TestArgs {
  val expWidth = 8
  val precision = 24
  val len = expWidth + precision

  def packLowHigh16(low: Int, high: Int): Int = {
    ((high & 0xffff) << 16) | (low & 0xffff)
  }

  def low16(x: BigInt): Int = (x & 0xffff).toInt

  def high16(x: BigInt): Int = ((x >> 16) & 0xffff).toInt

  def floatBits(f: Float): Int = java.lang.Float.floatToIntBits(f)

  def bf16ToFp32Bits(bits: Int): Int = (bits & 0xffff) << 16

  def fp32ToBf16Bits(bits: Int, rm: Int): Int = {
    val sign = (bits >>> 31) & 0x1
    val upper = (bits >>> 16) & 0xffff
    val lower = bits & 0xffff
    if (lower == 0) {
      upper
    } else {
      rm match {
        case 0 =>
          val lsb = upper & 0x1
          val roundBit = (lower >>> 15) & 0x1
          val sticky = lower & 0x7fff
          upper + (if (roundBit == 1 && (sticky != 0 || lsb == 1)) 1 else 0)
        case 1 => upper
        case 2 => upper + (if (sign == 1) 1 else 0)
        case 3 => upper + (if (sign == 0) 1 else 0)
        case 4 =>
          val roundBit = (lower >>> 15) & 0x1
          upper + (if (roundBit == 1) 1 else 0)
        case _ => upper
      }
    }
  }

  def toUInt[T <: AnyVal](f: T): String = {
    if(f.getClass == classOf[java.lang.Float]) {
      "h" + java.lang.Float.floatToIntBits(f.asInstanceOf[Float]).toHexString
    }
    else if(f.getClass == classOf[java.lang.Integer]) {
      "h" + java.lang.Integer.toHexString(f.asInstanceOf[Int])
    } else {
      "h0"
    }
  }
}
