package luau.core.codec

import luau.core.*
import luau.core.fake.*
import munit.FunSuite

class CodecSpec extends FunSuite:

  def encode[A: LuauEncoder](value: A): FakeState =
    val s = FakeBinding.newState()
    FakeBinding.pushEncoded(s, value)
    s

  def decode[A: LuauDecoder](s: FakeState, idx: Int = -1): Either[LuaError, A] =
    FakeBinding.decodeAt[A](s, idx)

  // ---- Primitives -------------------------------------------------------

  test("encode Double roundtrip") {
    val s = encode(3.14)
    assert(decode[Double](s).contains(3.14))
  }

  test("encode Boolean true") {
    val s = encode(true)
    assert(decode[Boolean](s).contains(true))
  }

  test("encode Boolean false") {
    val s = encode(false)
    assert(decode[Boolean](s).contains(false))
  }

  test("encode Int as Double") {
    val s = encode(42)
    assert(decode[Double](s).contains(42.0))
  }

  test("encode nil (Unit)") {
    val s = encode(())
    assert(decode[Unit](s).isRight)
    assert(FakeBinding.isNil(s, -1))
  }

  // ---- String -----------------------------------------------------------

  test("encode String as UTF-8") {
    val s = encode("hello")
    val decoded = decode[String](s)
    assert(decoded.contains("hello"))
  }

  test("encode String with non-ASCII (UTF-8)") {
    val s = encode("日本語")
    val decoded = decode[String](s)
    assert(decoded.contains("日本語"))
  }

  test("encode raw bytes roundtrip") {
    val bytes: IArray[Byte] = IArray(0xDE.toByte, 0xAD.toByte, 0xBE.toByte, 0xEF.toByte)
    val s = encode(bytes)
    assert(decode[IArray[Byte]](s).exists(_.sameElements(bytes)))
  }

  // ---- Option -----------------------------------------------------------

  test("encode Some(42.0)") {
    val s = encode(Some(42.0): Option[Double])
    assert(decode[Double](s).contains(42.0))
  }

  test("encode None as nil") {
    val s = encode(None: Option[Double])
    assert(FakeBinding.isNil(s, -1))
  }

  test("decode Option: nil -> None") {
    val s = FakeBinding.newState()
    FakeBinding.pushNil(s)
    assert(decode[Option[Double]](s).contains(None))
  }

  test("decode Option: number -> Some") {
    val s = FakeBinding.newState()
    FakeBinding.pushNumber(s, 7.0)
    assert(decode[Option[Double]](s).contains(Some(7.0)))
  }

  // ---- Seq / List -------------------------------------------------------

  test("encode Seq[Double] as 1-indexed table") {
    val s = encode(Seq(10.0, 20.0, 30.0))
    val decoded = decode[Seq[Double]](s)
    assert(decoded.contains(Seq(10.0, 20.0, 30.0)))
  }

  test("encode empty Seq as empty table") {
    val s = encode(Seq.empty[Double])
    val decoded = decode[Seq[Double]](s)
    assert(decoded.contains(Seq.empty))
  }

  // ---- Map[String, V] ---------------------------------------------------

  test("encode Map[String, Double]") {
    val input = Map("x" -> 1.0, "y" -> 2.0)
    val s = encode(input)
    FakeBinding.pushEncoded(s, "x")
    FakeBinding.rawGet(s, -2)
    assert(FakeBinding.toNumber(s, -1).contains(1.0))
  }

  // ---- Case class derivation --------------------------------------------

  case class Point(x: Double, y: Double) derives LuauEncoder, LuauDecoder

  test("derive LuauEncoder for case class") {
    val s = encode(Point(3.0, 4.0))
    FakeBinding.pushEncoded(s, "x")
    FakeBinding.rawGet(s, -2)
    assert(FakeBinding.toNumber(s, -1).contains(3.0))
  }

  test("derive LuauDecoder for case class") {
    val s = encode(Point(5.0, 6.0))
    val decoded = decode[Point](s)
    assert(decoded.contains(Point(5.0, 6.0)))
  }
