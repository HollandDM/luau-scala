package luau.wasm

import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array

object WasmMarshal:

  private val utf8Encoder = js.Dynamic.newInstance(js.Dynamic.global.selectDynamic("TextEncoder"))()
  private val utf8Decoder = js.Dynamic.newInstance(js.Dynamic.global.selectDynamic("TextDecoder"))("utf-8")

  def withString[A](s: String)(f: (Int, Int) => A): A =
    val bytes: Uint8Array = utf8Encoder.encode(s).asInstanceOf[Uint8Array]
    val len = bytes.length
    val ptr = WasmModule.module._malloc(len + 1)
    require(ptr != 0, s"_malloc(${len + 1}) returned null — WASM OOM")
    try
      val heap = WasmModule.module.HEAPU8
      heap.set(bytes, ptr)
      heap(ptr + len) = 0
      f(ptr, len)
    finally
      WasmModule.module._free(ptr)

  def withBytes[A](bytes: Array[Byte])(f: (Int, Int) => A): A =
    val len = bytes.length
    val ptr = WasmModule.module._malloc(len)
    require(ptr != 0, s"_malloc($len) returned null — WASM OOM")
    try
      val heap = WasmModule.module.HEAPU8
      var i = 0
      while i < len do
        heap(ptr + i) = (bytes(i) & 0xff).toShort
        i += 1
      f(ptr, len)
    finally
      WasmModule.module._free(ptr)

  def withIArrayBytes[A](bytes: IArray[Byte])(f: (Int, Int) => A): A =
    val len = bytes.length
    val ptr = WasmModule.module._malloc(len)
    require(ptr != 0, s"_malloc($len) returned null — WASM OOM")
    try
      val heap = WasmModule.module.HEAPU8
      var i = 0
      while i < len do
        heap(ptr + i) = (bytes(i) & 0xff).toShort
        i += 1
      f(ptr, len)
    finally
      WasmModule.module._free(ptr)

  def readString(strPtr: Int, len: Int): String =
    if strPtr == 0 || len == 0 then ""
    else
      val heap = WasmModule.module.HEAPU8
      val slice = heap.subarray(strPtr, strPtr + len)
      utf8Decoder.decode(slice).asInstanceOf[String]

  def allocOutInt(): (Int, () => Int) =
    val ptr = WasmModule.module._malloc(4)
    require(ptr != 0, "_malloc(4) returned null — WASM OOM")
    val read = () =>
      val heap = WasmModule.module.HEAPU8
      (heap(ptr).toInt & 0xff) |
      ((heap(ptr + 1).toInt & 0xff) << 8) |
      ((heap(ptr + 2).toInt & 0xff) << 16) |
      ((heap(ptr + 3).toInt & 0xff) << 24)
    (ptr, read)
