package luau.core.codec

import luau.core.*
import scala.compiletime.*
import scala.deriving.*

trait LuauDecoder[A]:
  def decode[H](binding: Binding[H], state: H, idx: Int): Either[LuaError, A]

object LuauDecoder:
  def apply[A](using dec: LuauDecoder[A]): LuauDecoder[A] = dec

  // ---- Primitive instances -------------------------------------------

  given LuauDecoder[Unit] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Unit] =
      if b.isNil(s, idx) then Right(())
      else Left(LuaError.runtime(s"expected nil at stack index $idx"))

  given LuauDecoder[Boolean] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Boolean] =
      Right(b.toBoolean(s, idx))

  given LuauDecoder[Double] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Double] =
      b.toNumber(s, idx).toRight(
        LuaError.runtime(s"expected number at stack index $idx, got ${b.typeAt(s, idx)}")
      )

  given LuauDecoder[Int] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Int] =
      b.toNumber(s, idx).map(_.toInt).toRight(
        LuaError.runtime(s"expected number (int) at stack index $idx")
      )

  given LuauDecoder[Long] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Long] =
      b.toNumber(s, idx).map(_.toLong).toRight(
        LuaError.runtime(s"expected number (long) at stack index $idx")
      )

  // ---- String / bytes -----------------------------------------------

  given LuauDecoder[IArray[Byte]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, IArray[Byte]] =
      b.toBytes(s, idx).toRight(
        LuaError.runtime(s"expected string at stack index $idx, got ${b.typeAt(s, idx)}")
      )

  given LuauDecoder[String] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, String] =
      b.toBytes(s, idx) match
        case None => Left(LuaError.runtime(s"expected string at stack index $idx"))
        case Some(bytes) =>
          try Right(new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
          catch case _: java.nio.charset.CharacterCodingException =>
            Left(LuaError.runtime(s"string at index $idx is not valid UTF-8"))

  // ---- Option --------------------------------------------------------

  given [A: LuauDecoder]: LuauDecoder[Option[A]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Option[A]] =
      if b.isNil(s, idx) then Right(None)
      else summon[LuauDecoder[A]].decode(b, s, idx).map(Some(_))

  // ---- Seq (1-indexed table -> Seq[A]) --------------------------------

  given [A: LuauDecoder]: LuauDecoder[Seq[A]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Seq[A]] =
      if b.typeAt(s, idx) != LuaType.Table then
        return Left(LuaError.runtime(s"expected table at $idx, got ${b.typeAt(s, idx)}"))
      val buf = scala.collection.mutable.ArrayBuffer.empty[A]
      var i   = 1
      while true do
        b.getArray(s, idx, i)
        if b.isNil(s, -1) then
          b.pop(s, 1)
          return Right(buf.toSeq)
        summon[LuauDecoder[A]].decode(b, s, -1) match
          case Right(a) => buf += a; b.pop(s, 1); i += 1
          case Left(e)  => b.pop(s, 1); return Left(e)
      Right(buf.toSeq)

  // ---- Map (string-keyed table -> Map[String, V]) --------------------
  // Full implementation deferred: requires Binding.tableNext (P04/P05).

  given [V: LuauDecoder]: LuauDecoder[Map[String, V]] with
    def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, Map[String, V]] =
      if b.typeAt(s, idx) != LuaType.Table then
        return Left(LuaError.runtime(s"expected table at $idx"))
      // TODO: implement via Binding.tableNext when added
      Right(Map.empty)

  // ---- Case class derivation -----------------------------------------

  inline def derived[A](using m: Mirror.ProductOf[A]): LuauDecoder[A] =
    val labels   = constValueTuple[m.MirroredElemLabels]
    val decoders = summonAll[Tuple.Map[m.MirroredElemTypes, LuauDecoder]]
    new LuauDecoder[A]:
      def decode[H](b: Binding[H], s: H, idx: Int): Either[LuaError, A] =
        if b.typeAt(s, idx) != LuaType.Table then
          return Left(LuaError.runtime(s"expected table for case class at $idx"))
        val values = new Array[Any](labels.productArity)
        var i = 0
        while i < values.length do
          val label = labels.productElement(i).toString
          b.pushString(s, label)
          b.rawGet(s, idx)
          decoders.productElement(i).asInstanceOf[LuauDecoder[Any]].decode(b, s, -1) match
            case Right(v) => values(i) = v; b.pop(s, 1); i += 1
            case Left(e)  => b.pop(s, 1); return Left(e)
        Right(m.fromProduct(
          new Product:
            def productArity = values.length
            def productElement(n: Int) = values(n)
            def canEqual(that: Any) = that.isInstanceOf[Product]
        ))
