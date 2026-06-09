package luau.panama

import java.lang.foreign.*
import java.lang.invoke.MethodHandle

object LxHandles:
  private val linker: Linker = Linker.nativeLinker()

  private val libPath = System.getProperty("luau.shim.lib")
  if libPath != null then System.load(libPath)
  else System.loadLibrary("luau-shim")
  private val lookup: SymbolLookup = SymbolLookup.loaderLookup()

  private def sym(name: String): MemorySegment =
    lookup.find(name).orElseThrow(() =>
      new UnsatisfiedLinkError(s"lx_* symbol not found: $name"))

  private def handle(name: String, desc: FunctionDescriptor): MethodHandle =
    linker.downcallHandle(sym(name), desc)

  import ValueLayout.*

  val HOST_FN_DESC: FunctionDescriptor = FunctionDescriptor.of(
    JAVA_INT,
    ADDRESS,
    ADDRESS,
    JAVA_INT,
    JAVA_INT,
    ADDRESS
  )

  val lx_newstate:    MethodHandle = handle("lx_newstate",    FunctionDescriptor.of(ADDRESS, ADDRESS))
  val lx_close:       MethodHandle = handle("lx_close",       FunctionDescriptor.ofVoid(ADDRESS))
  val lx_main_thread: MethodHandle = handle("lx_main_thread", FunctionDescriptor.of(ADDRESS, ADDRESS))
  val lx_new_thread:  MethodHandle = handle("lx_new_thread",  FunctionDescriptor.of(ADDRESS, ADDRESS))
  val lx_thread_status: MethodHandle = handle("lx_thread_status",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

  val lx_compile_and_load: MethodHandle = handle("lx_compile_and_load",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS,
      JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG))

  val lx_resume: MethodHandle = handle("lx_resume",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))

  val lx_push_nil:     MethodHandle = handle("lx_push_nil",     FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
  val lx_push_boolean: MethodHandle = handle("lx_push_boolean", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
  val lx_push_number:  MethodHandle = handle("lx_push_number",  FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_DOUBLE))
  val lx_push_integer: MethodHandle = handle("lx_push_integer", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG))
  val lx_push_lstring: MethodHandle = handle("lx_push_lstring",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
  val lx_push_ref:   MethodHandle = handle("lx_push_ref",   FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
  val lx_push_copy:  MethodHandle = handle("lx_push_copy",  FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
  val lx_pop:        MethodHandle = handle("lx_pop",        FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
  val lx_stack_top:  MethodHandle = handle("lx_stack_top",  FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))

  val lx_type:      MethodHandle = handle("lx_type",      FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
  val lx_to_number: MethodHandle = handle("lx_to_number",
    FunctionDescriptor.of(JAVA_DOUBLE, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
  val lx_to_integer: MethodHandle = handle("lx_to_integer",
    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))
  val lx_to_boolean: MethodHandle = handle("lx_to_boolean",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
  val lx_to_lstring: MethodHandle = handle("lx_to_lstring",
    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS))
  val lx_rawlen: MethodHandle = handle("lx_rawlen",
    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT))

  val lx_newtable: MethodHandle = handle("lx_newtable",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT))
  val lx_rawget:  MethodHandle = handle("lx_rawget",  FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
  val lx_rawset:  MethodHandle = handle("lx_rawset",  FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT))
  val lx_rawgeti: MethodHandle = handle("lx_rawgeti", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT))
  val lx_rawseti: MethodHandle = handle("lx_rawseti", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT))
  val lx_setarray: MethodHandle = handle("lx_setarray",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT))

  val lx_ref:   MethodHandle = handle("lx_ref",   FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
  val lx_unref: MethodHandle = handle("lx_unref", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))

  val lx_register_native: MethodHandle = handle("lx_register_native",
    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS))

  val lx_set_suspend_token: MethodHandle = handle("lx_set_suspend_token",
    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG))
  val lx_get_suspend_token: MethodHandle = handle("lx_get_suspend_token",
    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS))

  val lx_open_libs:  MethodHandle = handle("lx_open_libs",  FunctionDescriptor.ofVoid(ADDRESS))
  val lx_gc_step:    MethodHandle = handle("lx_gc_step",    FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT))
  val lx_gc_collect: MethodHandle = handle("lx_gc_collect", FunctionDescriptor.ofVoid(ADDRESS))

  val lx_copy_error: MethodHandle = handle("lx_copy_error",
    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG))
