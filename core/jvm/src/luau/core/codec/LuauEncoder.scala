package luau.core.codec

import luau.core.*
import scala.compiletime.*
import scala.deriving.*

trait LuauEncoder[A]:
  def encode[H](value: A, sink: Sink[H]): Unit

object LuauEncoder:
  def apply[A](using enc: LuauEncoder[A]): LuauEncoder[A] = enc

  def encode[A: LuauEncoder, H](value: A, sink: Sink[H]): Unit =
    summon[LuauEncoder[A]].encode(value, sink)

  // ---- Primitive instances -------------------------------------------

  given LuauEncoder[Unit]:
    def encode[H](value: Unit, sink: Sink[H]): Unit = sink.pushNil()

  given LuauEncoder[Boolean]:
    def encode[H](value: Boolean, sink: Sink[H]): Unit = sink.pushBoolean(value)

  given LuauEncoder[Double]:
    def encode[H](value: Double, sink: Sink[H]): Unit = sink.pushNumber(value)

  given LuauEncoder[Int]:
    def encode[H](value: Int, sink: Sink[H]): Unit = sink.pushNumber(value.toDouble)

  given LuauEncoder[Long]:
    def encode[H](value: Long, sink: Sink[H]): Unit = sink.pushNumber(value.toDouble)

  given LuauEncoder[Float]:
    def encode[H](value: Float, sink: Sink[H]): Unit = sink.pushNumber(value.toDouble)

  // ---- String instances ----------------------------------------------

  given LuauEncoder[String]:
    def encode[H](value: String, sink: Sink[H]): Unit = sink.pushString(value)

  given LuauEncoder[IArray[Byte]]:
    def encode[H](value: IArray[Byte], sink: Sink[H]): Unit = sink.pushBytes(value)

  given LuauEncoder[Array[Byte]]:
    def encode[H](value: Array[Byte], sink: Sink[H]): Unit =
      sink.pushBytes(IArray.unsafeFromArray(value))

  // ---- Option --------------------------------------------------------

  given [A: LuauEncoder] => LuauEncoder[Option[A]]:
    def encode[H](value: Option[A], sink: Sink[H]): Unit = value match
      case None    => sink.pushNil()
      case Some(a) => summon[LuauEncoder[A]].encode(a, sink)

  // ---- Collections (Seq / List / Array / Vector -> 1-indexed table) --

  given [A: LuauEncoder] => LuauEncoder[Seq[A]]:
    def encode[H](value: Seq[A], sink: Sink[H]): Unit =
      sink.beginTable()
      var i = 1
      for elem <- value do
        sink.pushArrayValue(i, elem)
        i += 1
      sink.endTable()

  given [A: LuauEncoder] => LuauEncoder[List[A]] =
    summon[LuauEncoder[Seq[A]]].asInstanceOf[LuauEncoder[List[A]]]

  given [A: LuauEncoder] => LuauEncoder[Array[A]]:
    def encode[H](value: Array[A], sink: Sink[H]): Unit =
      sink.beginTable()
      var i = 1
      for elem <- value do
        sink.pushArrayValue(i, elem)
        i += 1
      sink.endTable()

  given [A: LuauEncoder] => LuauEncoder[Vector[A]]:
    def encode[H](value: Vector[A], sink: Sink[H]): Unit =
      sink.beginTable()
      var i = 1
      for elem <- value do
        sink.pushArrayValue(i, elem)
        i += 1
      sink.endTable()

  // ---- Map (String keys -> table) ------------------------------------

  given [V: LuauEncoder] => LuauEncoder[Map[String, V]]:
    def encode[H](value: Map[String, V], sink: Sink[H]): Unit =
      sink.beginTable()
      for (k, v) <- value do
        sink.pushField(k, v)
      sink.endTable()

  // ---- Case class derivation via Mirror ------------------------------

  inline def derived[A](using m: Mirror.ProductOf[A]): LuauEncoder[A] =
    val labels    = constValueTuple[m.MirroredElemLabels]
    val encoders  = summonAll[Tuple.Map[m.MirroredElemTypes, LuauEncoder]]
    derivedEncoder[A](labels, encoders)

  private def derivedEncoder[A](labels: Tuple, encoders: Tuple): LuauEncoder[A] =
    new LuauEncoder[A] {
      def encode[H](value: A, sink: Sink[H]): Unit =
        val product = value.asInstanceOf[Product]
        sink.beginTable()
        var i = 0
        while i < product.productArity do
          val label = labels.productElement(i).toString
          sink.pushKey(LuaValue.LuaString.fromUtf8(label))
          encoders.productElement(i).asInstanceOf[LuauEncoder[Any]]
            .encode(product.productElement(i), sink)
          i += 1
        sink.endTable()
    }
