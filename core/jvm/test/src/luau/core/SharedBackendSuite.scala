package luau.core

import munit.FunSuite

abstract class SharedBackendSuite extends FunSuite:

  def withBinding[A](f: Binding[Int] => A): A

  test("TC-SHARED-01 basic execution returns integer"):
    withBinding { b =>
      val state = b.newState()
      try
        val source = IArray.unsafeFromArray("return 42".getBytes("UTF-8"))
        b.compileAndLoad(state, source, "test01").fold(e => fail(e.message), identity)
        val result = b.resume(state, 0)
        result match
          case ResumeResult.Returned(n) =>
            assertEquals(n, 1)
            assert(b.toNumber(state, -1).contains(42.0))
            b.pop(state, 1)
          case other => fail(s"expected Returned, got $other")
      finally b.closeState(state)
    }

  test("TC-SHARED-02 string push and read back"):
    withBinding { b =>
      val state = b.newState()
      try
        val s = "hello, 世界"
        b.pushString(state, s)
        val readBack = b.toBytes(state, -1).map(bytes =>
          new String(IArray.genericWrapArray(bytes).toArray, "UTF-8")
        )
        assert(readBack.contains(s))
        b.pop(state, 1)
      finally b.closeState(state)
    }

  test("TC-SHARED-03 table construction via rawseti/rawgeti"):
    withBinding { b =>
      val state = b.newState()
      try
        b.newTable(state)
        b.pushNumber(state, 1.0)
        b.setArray(state, -2, 1)
        b.pushNumber(state, 2.0)
        b.setArray(state, -2, 2)
        b.getArray(state, -2, 1)
        assert(b.toNumber(state, -1).contains(1.0))
        b.pop(state, 1)
        b.getArray(state, -2, 2)
        assert(b.toNumber(state, -1).contains(2.0))
        b.pop(state, 2)
      finally b.closeState(state)
    }

  test("TC-SHARED-04 native function is callable from script"):
    withBinding { b =>
      val state = b.newState()
      try
        var called = false
        val fn: NativeFn[Int] = (s, nargs) =>
          called = true
          val a = b.toNumber(s, 1).get
          val bb = b.toNumber(s, 2).get
          b.pushNumber(s, a + bb)
          NativeFnResult.Return(1)
        b.registerNativeFn(state, fn)
        b.setGlobal(state, "hostAdd")
        val src = IArray.unsafeFromArray("return hostAdd(10, 32)".getBytes("UTF-8"))
        b.compileAndLoad(state, src, "test04").fold(e => fail(e.message), identity)
        b.resume(state, 0)
        assert(called)
        assert(b.toNumber(state, -1).contains(42.0))
        b.pop(state, 1)
      finally b.closeState(state)
    }

  test("TC-SHARED-05 native function Fail raises Lua error"):
    withBinding { b =>
      val state = b.newState()
      try
        b.openLibs(state, 0)
        val fn: NativeFn[Int] = (s, _) =>
          b.pushString(s, "deliberate error")
          NativeFnResult.Fail(LuaValue.Nil)
        b.registerNativeFn(state, fn)
        b.setGlobal(state, "willFail")
        val src = IArray.unsafeFromArray(
          "local ok, err = pcall(willFail)\nreturn ok, err".getBytes("UTF-8")
        )
        b.compileAndLoad(state, src, "test05").fold(e => fail(e.message), identity)
        b.resume(state, 0)
        assertEquals(b.toBoolean(state, -2), false)
        val errMsg = b.toBytes(state, -1).map(bytes =>
          new String(IArray.genericWrapArray(bytes).toArray, "UTF-8")
        )
        assert(errMsg.exists(_.contains("deliberate error")))
        b.pop(state, 2)
      finally b.closeState(state)
    }

  test("TC-SHARED-06 Ref lifecycle: create, push, close"):
    withBinding { b =>
      val state = b.newState()
      try
        b.openLibs(state, 0)
        val src = IArray.unsafeFromArray("return {sentinel=true}".getBytes("UTF-8"))
        b.compileAndLoad(state, src, "test06").fold(e => fail(e.message), identity)
        b.resume(state, 0)
        val ref = b.ref(state)
        assertEquals(b.stackTop(state), 0)
        ref.push()
        b.pushString(state, "sentinel")
        b.rawGet(state, -2)
        assert(b.toBoolean(state, -1))
        b.pop(state, 2)
        ref.close()
      finally b.closeState(state)
    }

  test("TC-SHARED-07 Scope closes all owned Refs on exit"):
    withBinding { b =>
      val state = b.newState()
      try
        b.openLibs(state, 0)
        val scope = b.openScope(state)
        val src = IArray.unsafeFromArray("return {}".getBytes("UTF-8"))
        b.compileAndLoad(state, src, "test07").fold(e => fail(e.message), identity)
        b.resume(state, 0)
        val ref = b.ref(state)
        scope.own(ref)
        ref.push()
        assertEquals(b.typeAt(state, -1), LuaType.Table)
        b.pop(state, 1)
        scope.close()
      finally b.closeState(state)
    }

  test("TC-SHARED-08 resume yields on coroutine.yield"):
    withBinding { b =>
      val state = b.newState()
      try
        b.openLibs(state, 0)
        val src = IArray.unsafeFromArray("return coroutine.yield(42)".getBytes("UTF-8"))
        b.compileAndLoad(state, src, "test08").fold(e => fail(e.message), identity)
        val first = b.resume(state, 0)
        assert(first.isInstanceOf[ResumeResult.Yielded], s"expected Yielded, got $first")
        val second = b.resume(state, 0)
        assert(second.isInstanceOf[ResumeResult.Returned], s"expected Returned, got $second")
      finally b.closeState(state)
    }

  test("TC-SHARED-09 UTF-8 multi-byte string preserved"):
    withBinding { b =>
      val state = b.newState()
      try
        val testStr = "日本語テスト:  null-safe 😀"
        b.pushString(state, testStr)
        val readBack = b.toBytes(state, -1).map(bytes =>
          new String(IArray.genericWrapArray(bytes).toArray, "UTF-8")
        )
        assertEquals(readBack, Some(testStr))
        b.pop(state, 1)
      finally b.closeState(state)
    }

  test("TC-SHARED-10 compile error is surfaced as Left"):
    withBinding { b =>
      val state = b.newState()
      try
        val src = IArray.unsafeFromArray("this is not valid luau @@@".getBytes("UTF-8"))
        val result = b.compileAndLoad(state, src, "test10")
        assert(result.isLeft, s"expected Left, got $result")
      finally b.closeState(state)
    }
