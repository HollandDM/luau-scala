package luau.core.conformance

/** Manifest of upstream Luau conformance scripts this embedding can run.
  *
  * Scripts live in the vendored submodule at shim/luau/tests/conformance and
  * are read in place at test runtime (LUAU_CONFORMANCE_DIR). Each script runs
  * to completion and returns the string "OK".
  *
  * Portability triage against the upstream harness
  * (shim/luau/tests/Conformance.test.cpp):
  *   - portable: needs only the stdlib plus the environment provided by
  *     lx_conformance_setup (loadstring, collectgarbage, silenced print,
  *     is_native stubs, makelud, sandbox + sandboxthread + _G).
  *   - iter.luau additionally needs the cYieldingIterator prelude below.
  *   - excluded: requires C++-side setup our Shim does not expose
  *     (codegen/is_native asserts, custom userdata ctors, allocator hooks,
  *     debugger/interrupt callbacks, RTTI from type analysis, fast flags).
  */
object ConformanceManifest:

  /** LX_LIB_STANDARD | LX_LIB_DEBUG — everything lx_openlibs can open. */
  val fullLibsMask: Int = (1 << 11) - 1

  val portable: Seq[String] = Seq(
    "apicalls.luau",
    "assert.luau",
    "attrib.luau",
    "basic.luau",
    "bitwise.luau",
    "buffers.luau",
    "calls.luau",
    "clear.luau",
    "closure.luau",
    "constructs.luau",
    "coroutine.luau",
    "datetime.luau",
    "debug.luau",
    "errors.luau",
    "events.luau",
    "exceptions.luau",
    "explicit_type_instantiations.luau",
    "ifelseexpr.luau",
    "interrupt.luau",
    "iter.luau",
    "iter_fenv.luau",
    "literals.luau",
    "locals.luau",
    "math.luau",
    "move.luau",
    "native_integer_spills.luau",
    "ndebug_upvalues.luau",
    "pm.luau",
    "safeenv.luau",
    "sort.luau",
    "strconv.luau",
    "stringinterp.luau",
    "strings.luau",
    "tables.luau",
    "tmerror.luau",
    "tpack.luau",
    "utf8.luau",
    "vararg.luau",
    "vector.luau",
    "vector_library.luau",
  )

  /** Not portable without C++ harness machinery; kept here as the explicit
    * skip list so nobody re-triages them from scratch.
    */
  val excluded: Map[String, String] = Map(
    "classes.luau"            -> "needs DebugLuauUserDefinedClasses fast flags",
    "coverage.luau"           -> "needs getcoverage C++ global + coverageLevel=2",
    "cyield.luau"             -> "needs C++ continuation helpers (lua_resumek)",
    "debugger.luau"           -> "needs breakpoint/debugstep C++ callbacks",
    "gc.luau"                 -> "needs setblockallocations allocator hook",
    "integers.luau"           -> "needs LuauIntegerType2/LuauIntegerLibrary fast flags",
    "integers_regspill.luau"  -> "hard assert(is_native()) — needs codegen",
    "native.luau"             -> "hard assert(is_native()) — needs codegen",
    "native_types.luau"       -> "hard assert(is_native()) — needs codegen",
    "native_userdata.luau"    -> "needs vec2/vertex/mat3 C++ userdata ctors",
    "pcall.luau"              -> "needs cxxthrow/resumeerror globals + OOM allocator",
    "types.luau"              -> "needs RTTI global from Luau::Frontend analysis",
    "udata_direct.luau"       -> "needs userdata direct-access C++ hooks",
    "userdata.luau"           -> "needs int64 C++ userdata ctor with metamethods",
  )

  /** Pure-Lua stand-ins for C helpers the upstream harness registers per-test.
    * Runs before lx_conformance_setup (so before the sandbox freezes globals).
    *
    * cYieldingIterator mirrors the upstream C closure: yield index+1 to the
    * enclosing coroutine, then deliver (index+1, index+1) to the for-loop.
    */
  val luaPrelude: String =
    """
      |function cYieldingIterator(max, index)
      |  if index >= max then
      |    return nil
      |  end
      |  coroutine.yield(index + 1)
      |  return index + 1, index + 1
      |end
      |""".stripMargin
