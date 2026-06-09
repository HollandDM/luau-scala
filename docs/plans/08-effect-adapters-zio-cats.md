# Plan 08 — Effect Adapters: ZIO and Cats Effect

## 1. Milestone & Goal

This plan delivers two cross-platform adapter modules — `zio` and `ce` — that give Scala developers an idiomatic, type-safe interface to luau-scala using their preferred effect system. Both modules are pure wrappers over the callback-based `core` layer; they add no new runtime logic, no new Shim symbols, and never touch Luau state directly. The goal is precise: lift every `core` concept (state lifecycle, Ref, Native function suspension, script execution) into the vocabulary of ZIO Scoped resources and `ZIO[R, E, A]` effects (in `zio`) and Cats Effect `Resource[F, _]` and `F[A]` effects (in `ce`). An application developer using either module never sees a raw callback, a `Resume`, or a `Cancel`; they see idiomatic resource acquisition, typed errors, and interruptible fibers. The modules are backend-agnostic: they work over the Panama backend on the JVM and the WASM backend in Scala.js, because they depend only on `core` abstractions, not on backend-specific types.

---

## 2. Dependencies

The following plans must be complete before this plan is implemented. The implementing agent must read each plan and understand the artifacts listed.

### P01 — `docs/plans/01-project-scaffold-and-build-toolchain.md`
- Provides the Mill cross-build with module declarations for `zio` and `ce` as cross-platform modules.
- The `build.sc` must already declare `object zio extends CrossPlatformModule` and `object ce extends CrossPlatformModule`, each depending on `core` and on the respective effect library.
- Dependency versions for `dev.zio::zio` and `org.typelevel::cats-effect` must be pinned in the version catalog.

### P03 — `docs/plans/03-core-abstractions.md`
Provides the following types that this plan consumes directly — do not redefine them:

| Symbol | Location in `core` | Used by |
|---|---|---|
| `Binding` | `core/src/Binding.scala` | Both adapters (state lifecycle) |
| `LuaError` | `core/src/LuaError.scala` | Error channel of effects |
| `Result` | `core/src/Result.scala` | Return value of script execution |
| `Ref` (extends `AutoCloseable`) | `core/src/Ref.scala` | Wrapped in `ZIO Scope` / CE `Resource` |
| `Scope` | `core/src/Scope.scala` | Implemented per-backend; opened via `Binding` |
| `Resume` (`Either[LuaError, Result] => Unit`) | `core/src/Async.scala` | One-shot callback the adapters feed |
| `Cancel` (`() => Unit`) | `core/src/Async.scala` | Mapped to ZIO interrupt / CE cancel |
| `Suspend(register: Resume => Cancel)` | `core/src/NativeFn.scala` | The ADT case that triggers async bridging |
| `NativeFnResult` (sealed: `Return` / `Fail` / `Suspend`) | `core/src/NativeFn.scala` | Return type of lifted Host functions |
| `LuauEncoder[A]`, `LuauDecoder[A]` | `core/src/Codec.scala` | Script argument / result marshaling |

### P06 — `docs/plans/06-scheduler-and-task-model.md`
- Provides the `Scheduler` type and its `submit` / `run` entry points.
- Provides the thread-safe `RunQueue` post mechanism that `Resume` enqueues onto; the adapters must not call `lua_resume` directly — they enqueue via the Scheduler.
- Provides `Task` lifecycle (spawn, cancel) that the adapters drive via effects.
- Running a script returns a `Task` handle the adapters must represent as an effect.

### A backend (P04 or P05)
- At least one of the Panama backend (JVM) or WASM backend (JS) must be complete for integration tests to run. Unit tests against the fake backend from P03 are sufficient for the core adapter logic.

---

## 3. Design Context

### 3.1 The Async Primitive (ADR-0007)

The callback-based Async primitive in `core` is the bedrock this plan builds on. A Native function that needs to await an asynchronous result returns:

```
Suspend(register: Resume => Cancel)
```

Where:
- `register` is a function the Scheduler calls exactly once, passing the one-shot `resume: Either[LuaError, Result] => Unit` callback.
- `register` must wire up the async operation — whatever it is — to call `resume` exactly once when done.
- `register` returns a `Cancel: () => Unit` that the Scheduler calls if the Task is torn down before `resume` fires.
- `resume` only *enqueues* onto the Run queue; it never calls `lua_resume` inline (ADR-0002, ADR-0004).

ZIO's `ZIO.asyncInterrupt` and Cats Effect's `Async[F].async` are already shaped as `(callback => cancellation)` — the mapping is direct. Neither adapter needs a new runtime primitive; they just translate surface syntax.

### 3.2 No Protected Calls Across the Boundary (ADR-0001)

The adapters never call into Luau state directly. They call `Scheduler` methods (which eventually call `lua_resume` on the Driver). If a Native function lifted from a ZIO effect fails (the ZIO fiber fails with `E`), the adapter maps the error to `NativeFnResult.Fail(...)`, which the Shim trampoline turns into `lua_error` in pure C. The JVM/JS frame is never on the call stack when `lua_error` fires.

### 3.3 Ref Lifetime and Deterministic Release (ADR-0005)

A `Ref` is `AutoCloseable` — it must be closed explicitly, not by a GC finalizer. The CONTEXT.md entry for `Ref` explicitly names `cats-effect Resource` and `ZIO Scoped` as the idiomatic owners. The adapters wrap `Ref` acquisition/release as:
- ZIO: `ZIO.acquireRelease(acquire)(ref => ZIO.succeed(ref.close()))` inside a `ZIO.scoped` block — the `Ref` lives exactly as long as the `Scope`.
- CE: `Resource.make(acquire)(ref => F.delay(ref.close()))`.

### 3.4 State Lifecycle as a Resource

The Luau state (`Binding` instance) must be closed when the effect resource is released. Both adapters expose the state as a top-level `Resource` / `Scoped` resource so that finalizers run deterministically on effect cancellation or error.

### 3.5 Single-Threaded Driver and Off-Driver Completions (ADR-0002, ADR-0004)

The Scheduler owns the state. A ZIO fiber or a CE fiber completing on an IO thread pool must post back to the Run queue via `Resume`, never resume inline. The adapter must not attempt to hold a reference to the Luau state across fiber boundaries — the state is Driver-owned; only the Run queue post is thread-safe.

### 3.6 Copy-Only Data Boundary (ADR-0006)

Arguments to scripts and return values from scripts cross the boundary via `LuauEncoder[A]` / `LuauDecoder[A]`. The adapters expose typed `runScript[In: LuauEncoder, Out: LuauDecoder]` methods. The Codec layer enforces this at compile time.

### 3.7 Stackless Tasks (ADR-0003)

A parked Task holds no native stack. The effect adapters do not need to worry about suspending OS threads — the Luau side is already parked in its heap. The ZIO fiber / CE fiber that supplies the result is independent; it completes and posts to the Run queue without holding any Luau resource.

---

## 4. Task Breakdown

The section below lists every file to create, its purpose, and the key declarations with enough detail that a filling agent can write the bodies without further context.

### 4.1 ZIO Module

#### `zio/src/luau/zio/LuauZIO.scala`

**Purpose:** The primary entry point for the ZIO adapter. Exposes the Luau state as a `ZLayer` / `ZIO.scoped` resource, provides `runScript`, and provides `nativeFn` lifting.

```scala
package luau.zio

import zio.*
import luau.core.{Binding, LuaError, Result, LuauEncoder, LuauDecoder}
import luau.core.NativeFn.{NativeFnResult, Return, Fail, Suspend}
import luau.core.Async.{Resume, Cancel}
import luau.scheduler.Scheduler

/** A live Luau state managed as a ZIO Scoped resource.
 *  Acquire opens the state; release closes it (including all owned Refs).
 *  Must be used within a ZIO Scope — do not hold LuauState across scope boundaries.
 */
final class LuauState private (
  val binding: Binding,
  val scheduler: Scheduler
)

object LuauState:

  /** Acquire a LuauState in the current ZIO Scope.
   *
   *  @param makeBinding  ZIO effect that produces a fresh Binding (backend-specific).
   *  @param makeScheduler  Function that builds a Scheduler over a Binding.
   *  @return Scoped LuauState; finalizer calls binding.close() and scheduler.shutdown().
   */
  def scoped(
    makeBinding: UIO[Binding],
    makeScheduler: Binding => Scheduler
  ): ZIO[Scope, LuaError, LuauState] =
    ZIO.acquireRelease(
      makeBinding.map { b => new LuauState(b, makeScheduler(b)) }
    )(state => ZIO.succeed { state.scheduler.shutdown(); state.binding.close() })
    .mapError(t => LuaError.Internal(t.getMessage))

  /** Convenience: make a ZLayer that provides LuauState in a Scope. */
  def layer(
    makeBinding: UIO[Binding],
    makeScheduler: Binding => Scheduler
  ): ZLayer[Scope, LuaError, LuauState] =
    ZLayer.fromZIO(scoped(makeBinding, makeScheduler))
```

#### `zio/src/luau/zio/ZioRef.scala`

**Purpose:** Wraps a `core.Ref` as a `ZIO.Scope`-managed resource so it is released deterministically. Also provides helpers to read the Ref's Luau value as a decoded Scala type within an effect.

```scala
package luau.zio

import zio.*
import luau.core.{Ref, Result, LuaError, LuauDecoder}

/** A Luau Ref whose lifetime is tied to a ZIO Scope. */
final class ZioRef private[zio] (val underlying: Ref)

object ZioRef:

  /** Open a Ref inside the current Scope.
   *  Calls underlying.close() when the Scope exits (cancel, error, or normal completion).
   *
   *  @param acquire  Effect that opens the Ref (e.g. state.binding.newRef(...)).
   *  @return ZioRef scoped to the current Scope.
   */
  def scoped(acquire: UIO[Ref]): ZIO[Scope, LuaError, ZioRef] =
    ZIO.acquireRelease(acquire.map(new ZioRef(_)))(zr =>
      ZIO.succeed(zr.underlying.close())
    )

  /** Read the Ref's table field as a decoded Scala value.
   *  Runs on the Driver (must be called within a runOnDriver block).
   *
   *  @param zr  The ZioRef to read from.
   *  @param key  Field name.
   *  @param D  Implicit decoder.
   */
  def readField[A: LuauDecoder](
    zr: ZioRef,
    key: String
  )(using LuauState): ZIO[Any, LuaError, A] = ???
  // Body: delegate to binding.readField(zr.underlying, key) on the Driver thread.
```

#### `zio/src/luau/zio/ZioNativeFn.scala`

**Purpose:** Lifts a Scala function `A => ZIO[R, LuaError, NativeFnResult]` into `core`'s `NativeFnResult`, bridging via `ZIO.asyncInterrupt` when the function returns `Suspend`. Also lifts a simpler `A => ZIO[R, LuaError, Result]` by wrapping the result in `Return`.

This is the **key technical file** in the ZIO adapter — read this section carefully.

The fundamental challenge: a Native function is called synchronously on the Driver (inside a `lua_resume` upcall). It must return a `NativeFnResult` immediately. If the Scala function needs to do async work (ZIO effect), we cannot block the Driver. Solution: the Native function returns `Suspend(register)` immediately. The `register` body captures a `Runtime[R]` and uses `ZIO.asyncInterrupt` to launch the ZIO effect, with the `resume` callback wired to the ZIO fiber's exit.

```scala
package luau.zio

import zio.*
import zio.Runtime as ZRuntime
import luau.core.NativeFn.{NativeFnResult, Return, Fail, Suspend as CoreSuspend}
import luau.core.Async.{Resume, Cancel}
import luau.core.{LuaError, Result, LuauDecoder, LuauEncoder}

object ZioNativeFn:

  /** Lift a ZIO effect-returning function into a NativeFn-compatible callback.
   *
   *  This version accepts the full NativeFnResult — use when the function wants to
   *  return multiple results (Return(n)) or unconditionally fail (Fail).
   *  Use `liftSimple` for the common case of returning a single encoded value.
   *
   *  The returned function has signature `() => NativeFnResult`, suitable for
   *  registration with `Binding.registerNativeFn`.
   *
   *  THREADING CONTRACT:
   *  - The returned lambda is called synchronously on the Driver.
   *  - It captures `runtime` and immediately returns `CoreSuspend(register)`.
   *  - `register` is called by the Scheduler on the Driver; it launches a ZIO fiber
   *    on the ZIO thread pool (via runtime.unsafe.fork) and returns a Cancel that
   *    interrupts that fiber.
   *  - The fiber calls `resume(Right(result))` or `resume(Left(err))` exactly once.
   *  - `resume` only enqueues onto the Run queue; it is thread-safe (ADR-0007).
   *
   *  @param runtime  A captured ZIO Runtime with the required environment R.
   *  @param effect   The ZIO effect to run when the Native function is called.
   *                  Receives no arguments here — argument reading from the Luau stack
   *                  must happen synchronously before this call, before the Driver
   *                  hands control back; pass them in via closure capture.
   */
  def lift[R](
    runtime: ZRuntime[R]
  )(
    effect: ZIO[R, LuaError, NativeFnResult]
  ): () => NativeFnResult =
    () =>
      CoreSuspend { (resume: Resume) =>
        // `register` body — called on the Driver, must return Cancel immediately.
        // We use runtime.unsafe.runToFuture (or fork) to launch off-Driver.
        var fiber: Fiber.Runtime[LuaError, NativeFnResult] = null
        val cancel: Cancel = () =>
          if fiber != null then
            runtime.unsafe.run(fiber.interrupt)(Trace.empty, Unsafe.unsafe)
        // Launch the fiber. The fiber runs on the ZIO thread pool.
        fiber = runtime.unsafe.fork(
          effect.foldCauseZIO(
            cause =>
              // Map ZIO failure/defect to LuaError, then call resume.
              ZIO.succeed {
                val err = cause.failureOption
                  .getOrElse(LuaError.Internal(cause.prettyPrint))
                resume(Left(err))
              },
            result =>
              ZIO.succeed {
                // result is a NativeFnResult; only Return/Fail make sense here.
                // Suspend inside a Suspend is not supported and must error.
                result match
                  case Return(n)  => resume(Right(Result.Values(n)))
                  case Fail(v)    => resume(Left(LuaError.Script(v)))
                  case _: CoreSuspend =>
                    resume(Left(LuaError.Internal(
                      "ZioNativeFn: nested Suspend in lifted effect is not supported"
                    )))
              }
          )
        )(Trace.empty, Unsafe.unsafe)
        cancel
      }

  /** Simplified lift: the effect returns a single decodable result.
   *  Pushes the encoded value as a single Luau return value.
   */
  def liftSimple[R, A: LuauEncoder](
    runtime: ZRuntime[R]
  )(
    effect: ZIO[R, LuaError, A]
  ): () => NativeFnResult =
    lift(runtime)(effect.map(a => Return(1) /* push A via encoder */ ))
    // NOTE: The implementing agent must wire the LuauEncoder push here.
    // The Native function is called with the Luau stack available; the effect result
    // must be pushed to the Luau stack BEFORE returning Return(1).
    // This requires the effect to run on the Driver, OR the push to happen via
    // a Binding.push call wrapped in a ZIO that runs on the Driver thread.
    // See Section 4.1.1 for the Driver-thread-push pattern.
```

> **Section 4.1.1 — Driver-thread push pattern for ZIO**
>
> ZIO fibers run on ZIO's thread pool, not on the Driver. Pushing a value onto the Luau stack (`binding.push[A](value)`) is only safe on the Driver. Therefore `liftSimple` and any adapter that needs to push a result must enqueue the push as part of the `Resume` payload.
>
> The correct approach is to pre-encode the result to a `core.Result` (a Scala value carrying the decoded/encoded data) before calling `resume`. The `resume` callback enqueues a `ResumePayload` onto the Run queue; the Scheduler, when it pops and resumes the Task on the Driver, performs the push as part of the resume prologue. This means the adapter must NOT call `binding.push` from a ZIO fiber thread.
>
> The `Resume` type is `Either[LuaError, Result] => Unit`. `Result` must carry a Scala-side representation (e.g., `Result.Values(n: Int)` where the values were already pushed, or a richer `Result.Encoded(data)` variant). This is a **P03 design detail** — the implementing agent must confirm that `Result` can carry a pre-push payload or that the Scheduler's resume path handles it. If `Result` only carries an `Int` (the number of already-pushed values), then the push must happen before `resume` is called, which requires a different threading pattern (a dedicated "Driver callback" mechanism). The plan recommends: agree with P03/P06 implementors on a `Result.Pending[A: LuauEncoder](a: A)` variant that the Scheduler pushes on resume.

#### `zio/src/luau/zio/ZioSchedulerDriver.scala`

**Purpose:** Drives the Scheduler's run loop as a ZIO fiber. On the JVM, runs the drain loop on a dedicated ZIO thread or executor. On JS (Scala.js), the Scheduler is event-loop driven — the ZIO driver just yields control to the JS event loop (uses `ZIO.yieldNow` between drains).

```scala
package luau.zio

import zio.*
import luau.scheduler.Scheduler

object ZioSchedulerDriver:

  /** Run the Scheduler's drain loop as a managed ZIO effect.
   *  The returned ZIO never completes normally — it runs until interrupted.
   *  Use ZIO.scoped or fork into a background fiber.
   *
   *  On JVM: runs on a blocking executor (ZIO.blocking) to avoid starving the ZIO pool.
   *  On JS: runs in microtask-friendly increments via ZIO.yieldNow.
   */
  def drive(scheduler: Scheduler): ZIO[Any, Nothing, Nothing] =
    ZIO.blocking(
      ZIO.repeatUntilZIO(
        ZIO.attempt(scheduler.drainOnce()).ignore
      )(_ => ZIO.succeed(false)) // never stops; interrupted from outside
    ).orDie

  /** Convenience: fork the driver loop in the background, tied to a Scope. */
  def forkScoped(scheduler: Scheduler): ZIO[Scope, Nothing, Fiber[Nothing, Nothing]] =
    ZioSchedulerDriver.drive(scheduler).forkScoped
```

#### `zio/src/luau/zio/ZioLuauScript.scala`

**Purpose:** Run a compiled Luau script as a ZIO effect. Accepts encoded inputs, returns decoded output. Internally: submit the script to the Scheduler as a Task, then use `ZIO.asyncInterrupt` to wait for Task completion via the `Resume` callback.

```scala
package luau.zio

import zio.*
import luau.core.{LuaError, Result, LuauEncoder, LuauDecoder}
import luau.scheduler.{Scheduler, TaskHandle}

object ZioLuauScript:

  /** Compile and run a Luau script, returning the decoded result.
   *
   *  The script is submitted to the Scheduler as a new Task.
   *  This ZIO suspends (via ZIO.asyncInterrupt) until the Task completes,
   *  errors, or the fiber is interrupted.
   *  On interruption, the Task's Cancel is called, stopping the Task on
   *  its next yield point.
   *
   *  @param state    The LuauState to run in.
   *  @param source   Luau source code string.
   *  @param chunkName  Debug name for error messages.
   *  @param args     Input arguments (encoded via LuauEncoder).
   *  @param D        Implicit decoder for the result type.
   *  @tparam In      Input type (must have LuauEncoder).
   *  @tparam Out     Output type (must have LuauDecoder).
   */
  def run[In: LuauEncoder, Out: LuauDecoder](
    state: LuauState,
    source: String,
    chunkName: String,
    args: In
  ): ZIO[Any, LuaError, Out] =
    ZIO.asyncInterrupt[Any, LuaError, Out] { callback =>
      // Submit the Task to the Scheduler. The Scheduler compiles + spawns.
      val handle: TaskHandle = state.scheduler.submit(source, chunkName, args)
      // Register the resume callback so we hear when it completes.
      handle.onComplete {
        case Right(result) =>
          // Decode result from core.Result into Out.
          result.decode[Out] match
            case Right(out) => callback(ZIO.succeed(out))
            case Left(err)  => callback(ZIO.fail(err))
        case Left(err) =>
          callback(ZIO.fail(err))
      }
      // Return the cancel: Left(canceller).
      Left(ZIO.succeed(handle.cancel()))
    }
```

#### `zio/src/luau/zio/package.scala`

**Purpose:** Convenience re-exports and extension methods. Makes the common API importable with `import luau.zio.*`.

```scala
package luau.zio

export ZioRef.{scoped as scopedRef}
export ZioNativeFn.{lift as liftNativeFn, liftSimple}
export ZioLuauScript.run as runScript
export LuauState.{scoped as scopedState, layer as stateLayer}
export ZioSchedulerDriver.{drive as driveScheduler, forkScoped as forkScheduler}
```

---

### 4.2 Cats Effect Module

#### `ce/src/luau/ce/LuauResource.scala`

**Purpose:** Cats Effect equivalent of `LuauState` — the state as a `cats.effect.Resource[F, LuauCEState]`.

```scala
package luau.ce

import cats.effect.{Resource, Async, Sync}
import cats.effect.std.Dispatcher
import luau.core.{Binding, LuaError}
import luau.scheduler.Scheduler

/** A live Luau state managed as a Cats Effect Resource. */
final class LuauCEState[F[_]](
  val binding: Binding,
  val scheduler: Scheduler,
  val dispatcher: Dispatcher[F]
)

object LuauCEState:

  /** Acquire a LuauCEState as a Cats Effect Resource.
   *
   *  Allocates a Dispatcher (for bridging CE fibers to callbacks),
   *  opens the Binding, and builds the Scheduler.
   *  Resource finalizer: shuts down the Scheduler, closes the Binding,
   *  then releases the Dispatcher.
   *
   *  @param makeBinding  F effect producing a fresh Binding.
   *  @param makeScheduler  Builds a Scheduler from a Binding.
   */
  def resource[F[_]: Async](
    makeBinding: F[Binding],
    makeScheduler: Binding => Scheduler
  ): Resource[F, LuauCEState[F]] =
    for
      dispatcher <- Dispatcher.parallel[F]
      binding    <- Resource.make(makeBinding)(b => Sync[F].delay(b.close()))
      scheduler  <- Resource.make(
                      Sync[F].delay(makeScheduler(binding))
                    )(s => Sync[F].delay(s.shutdown()))
    yield new LuauCEState(binding, scheduler, dispatcher)
```

Note on `Dispatcher`: the `Dispatcher[F]` is obtained via `Dispatcher.parallel[F]` (or `Dispatcher.sequential` for JS where the event loop is single-threaded). The Dispatcher allows calling `dispatcher.unsafeRunAndForget(effect)` from outside the CE runtime — specifically from the `register` body inside `CoreSuspend`, which is called synchronously on the Driver thread and needs to launch a CE fiber without being inside `F`.

#### `ce/src/luau/ce/CERef.scala`

**Purpose:** Wraps a `core.Ref` as a Cats Effect `Resource[F, CERef[F]]`.

```scala
package luau.ce

import cats.effect.{Resource, Sync}
import luau.core.{Ref, LuaError, LuauDecoder, Result}

final class CERef[F[_]](val underlying: Ref)

object CERef:

  /** Open a core.Ref as a Cats Effect Resource.
   *  The Ref is closed (luaL_unref) when the Resource is released.
   *
   *  @param acquire  F effect that opens the Ref (must run on the Driver).
   */
  def resource[F[_]: Sync](acquire: F[Ref]): Resource[F, CERef[F]] =
    Resource.make(acquire.map(new CERef[F](_)))(cr =>
      Sync[F].delay(cr.underlying.close())
    )
```

#### `ce/src/luau/ce/CENativeFn.scala`

**Purpose:** Lifts a Cats Effect `F[NativeFnResult]` into a `core` Native function callback via `Async[F].async` / `Async[F].asyncInterrupt`. Uses the `Dispatcher[F]` (captured from `LuauCEState`) to launch the fiber from the Driver thread synchronously.

```scala
package luau.ce

import cats.effect.{Async, Sync}
import cats.effect.std.Dispatcher
import luau.core.NativeFn.{NativeFnResult, Return, Fail, Suspend as CoreSuspend}
import luau.core.Async.{Resume, Cancel}
import luau.core.{LuaError, Result, LuauEncoder}

object CENativeFn:

  /** Lift a Cats Effect F[NativeFnResult] into a NativeFn-compatible callback.
   *
   *  THREADING CONTRACT:
   *  - The returned lambda is called synchronously on the Driver.
   *  - It returns CoreSuspend(register) immediately.
   *  - register is called by the Scheduler; it uses dispatcher.unsafeRunAndForget
   *    to launch the CE fiber off the Driver.
   *  - The fiber calls resume(Right/Left) exactly once.
   *  - Cancellation: the CE fiber is cancelled via the cancel token returned
   *    by Async[F].allocate or a Deferred-based mechanism.
   *
   *  @param dispatcher  A Dispatcher[F] from LuauCEState — allows launching F[_]
   *                     fibers from non-CE (callback) contexts.
   *  @param effect      The CE effect to run.
   */
  def lift[F[_]: Async](
    dispatcher: Dispatcher[F]
  )(
    effect: F[NativeFnResult]
  ): () => NativeFnResult =
    () =>
      CoreSuspend { (resume: Resume) =>
        // Allocate a cancellation latch.
        var cancelF: F[Unit] = Async[F].unit
        val javaCancel: Cancel = () => dispatcher.unsafeRunAndForget(cancelF)

        val fiberEffect: F[Unit] =
          Async[F].async_[NativeFnResult] { cb =>
            dispatcher.unsafeRunAndForget(
              effect.attempt.flatMap {
                case Right(r) => Sync[F].delay(cb(Right(r)))
                case Left(e)  => Sync[F].delay(cb(Left(e)))
              }
            )
          }.flatMap {
            case Return(n)         => Sync[F].delay(resume(Right(Result.Values(n))))
            case Fail(v)           => Sync[F].delay(resume(Left(LuaError.Script(v))))
            case _: CoreSuspend =>
              Sync[F].delay(resume(Left(LuaError.Internal(
                "CENativeFn: nested Suspend in lifted effect is not supported"
              ))))
          }.handleError { t =>
            resume(Left(LuaError.Internal(t.getMessage)))
          }

        dispatcher.unsafeRunAndForget(fiberEffect)
        javaCancel
      }

  /** Simplified lift returning a single decodable value. */
  def liftSimple[F[_]: Async, A: LuauEncoder](
    dispatcher: Dispatcher[F]
  )(
    effect: F[A]
  ): () => NativeFnResult =
    lift(dispatcher)(effect.map(_ => Return(1)))
    // See Section 4.1.1 re: Driver-thread push; same pattern applies here.
```

> **Note on `Dispatcher` choice:** Use `Dispatcher.parallel[F]` on JVM (multiple fibers can be in flight). On JS, use `Dispatcher.sequential[F]` — the JS event loop is single-threaded and `parallel` may cause unexpected interleaving. The `LuauCEState.resource` constructor should accept a `parallel: Boolean` parameter, defaulting to `true` on JVM and `false` on JS (platform-specific given in a separate `ce/jvm/` and `ce/js/` source tree).

#### `ce/src/luau/ce/CESchedulerDriver.scala`

**Purpose:** Drives the Scheduler's drain loop as a Cats Effect stream/fiber. Pattern mirrors `ZioSchedulerDriver`.

```scala
package luau.ce

import cats.effect.{Async, Sync, Resource}
import cats.effect.syntax.all.*
import fs2.Stream
import luau.scheduler.Scheduler

object CESchedulerDriver:

  /** Run the Scheduler drain loop as a never-completing F effect.
   *  On JVM: runs on a blocking thread pool via Sync[F].blocking.
   *  On JS: yields between drains via Async[F].cede.
   */
  def drive[F[_]: Async](scheduler: Scheduler): F[Nothing] =
    Stream
      .repeatEval(
        Sync[F].blocking(scheduler.drainOnce())
          .handleError(_ => ())
      )
      .compile
      .drain
      .flatMap(_ => Async[F].never)

  /** Fork the driver in the background, releasing on Resource exit. */
  def driverResource[F[_]: Async](scheduler: Scheduler): Resource[F, Unit] =
    drive(scheduler).background.void
```

Note: `fs2` is a transitive dependency of `cats-effect` in most CE ecosystems. If the project does not use `fs2`, replace the `Stream.repeatEval` with a plain tail-recursive `F` loop:

```scala
def drive[F[_]: Async](scheduler: Scheduler): F[Nothing] =
  Async[F].defer(
    Sync[F].blocking(scheduler.drainOnce())
      .handleError(_ => ())
      .flatMap(_ => drive(scheduler))
  )
```

#### `ce/src/luau/ce/CELuauScript.scala`

**Purpose:** Run a Luau script as a CE `F[Out]`. Mirrors `ZioLuauScript.run`.

```scala
package luau.ce

import cats.effect.Async
import luau.core.{LuaError, Result, LuauEncoder, LuauDecoder}
import luau.scheduler.{Scheduler, TaskHandle}

object CELuauScript:

  /** Compile and run a Luau script, returning the decoded result as F[Out].
   *
   *  Uses Async[F].async (interruptible) so that fiber cancellation propagates
   *  to the Luau Task via TaskHandle.cancel().
   *
   *  @param state    The LuauCEState.
   *  @param source   Luau source code string.
   *  @param chunkName  Debug name.
   *  @param args     Input arguments.
   */
  def run[F[_]: Async, In: LuauEncoder, Out: LuauDecoder](
    state: LuauCEState[F],
    source: String,
    chunkName: String,
    args: In
  ): F[Out] =
    Async[F].async[Out] { callback =>
      // Submit the Task. Returns a handle.
      val handle: TaskHandle = state.scheduler.submit(source, chunkName, args)
      // Register completion listener.
      handle.onComplete {
        case Right(result) =>
          result.decode[Out] match
            case Right(out) => callback(Right(out))
            case Left(err)  => callback(Left(new LuaRuntimeException(err)))
        case Left(err) =>
          callback(Left(new LuaRuntimeException(err)))
      }
      // Return Some(cancellation F) for interruptible async.
      // cats.effect.Async.async expects F[Option[F[Unit]]].
      // Wrapping in Async[F].pure: finalizer to cancel the task if fiber is cancelled.
      Async[F].pure(Some(Async[F].delay(handle.cancel())))
    }
```

Note: the CE `Async[F].async` callback variant expects a return of `F[Option[F[Unit]]]` where `Some(cancel)` makes the suspension interruptible. The implementing agent should confirm the exact signature for the CE version in use (CE 3.x uses `async` with `(Either[Throwable, A] => Unit) => F[Option[F[Unit]]]`).

#### `ce/src/luau/ce/LuaRuntimeException.scala`

**Purpose:** Bridge `LuaError` (a `core` ADT) into `Throwable` for Cats Effect's error channel (which is fixed at `Throwable` for `IO`, unlike ZIO's typed error channel).

```scala
package luau.ce

import luau.core.LuaError

/** Wraps a LuaError as a Throwable for Cats Effect's IO error channel.
 *  Use CE's typed F[Either[LuaError, A]] if you need to preserve LuaError type.
 */
final class LuaRuntimeException(val error: LuaError)
  extends RuntimeException(error.message, null, true, false)

object LuaRuntimeException:
  def unapply(e: Throwable): Option[LuaError] = e match
    case lre: LuaRuntimeException => Some(lre.error)
    case _                        => None
```

#### `ce/src/luau/ce/package.scala`

**Purpose:** Convenience re-exports.

```scala
package luau.ce

export CERef.resource as ceRefResource
export CENativeFn.{lift as liftNativeFn, liftSimple}
export CELuauScript.run as runScript
export LuauCEState.resource as ceStateResource
export CESchedulerDriver.{drive as driveScheduler, driverResource}
```

---

### 4.3 Build Configuration

#### `build.sc` additions (fragment — not a new file, modify existing)

The implementing agent must add these module definitions to the existing `build.sc` from P01. Listed here for completeness:

```scala
// In build.sc — add to the cross-platform module declarations:

object zio extends CrossPlatformModule:
  def moduleDeps = Seq(core, scheduler)
  def ivyDeps = Agg(
    ivy"dev.zio::zio::2.1.x"  // pin to project-wide version catalog
  )
  // Test deps:
  object test extends CrossPlatformTests:
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"dev.zio::zio-test::2.1.x",
      ivy"dev.zio::zio-test-sbt::2.1.x"
    )

object ce extends CrossPlatformModule:
  def moduleDeps = Seq(core, scheduler)
  def ivyDeps = Agg(
    ivy"org.typelevel::cats-effect::3.5.x"
    // fs2 optional — add if CESchedulerDriver uses Stream:
    // ivy"co.fs2::fs2-core::3.x.x"
  )
  object test extends CrossPlatformTests:
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"org.typelevel::cats-effect-testing-scalatest::1.5.x"
      // or munit-cats-effect
    )
```

---

### 4.4 Test Files

#### `zio/test/luau/zio/ZioAdapterSpec.scala`

```scala
package luau.zio

import zio.*
import zio.test.*
import luau.core.fake.FakeBinding  // from P03 fake backend
import luau.scheduler.Scheduler

object ZioAdapterSpec extends ZIOSpecDefault:

  val fakeState: ZIO[Scope, Nothing, LuauState] =
    LuauState.scoped(
      ZIO.succeed(new FakeBinding),
      binding => new Scheduler(binding)
    )

  def spec = suite("ZioAdapter")(

    test("scopedRef — acquires and releases") { /* ... */ },

    test("liftNativeFn — ZIO effect completes and resumes Task") { /* ... */ },

    test("liftNativeFn — ZIO effect fails and propagates LuaError") { /* ... */ },

    test("liftNativeFn — fiber interruption triggers Cancel") { /* ... */ },

    test("runScript — simple script returns decoded value") { /* ... */ },

    test("runScript — script error propagates as LuaError") { /* ... */ },

    test("runScript — fiber interruption cancels the Task") { /* ... */ },

    test("nested Suspend inside liftNativeFn — returns Internal error") { /* ... */ }
  )
```

#### `ce/test/luau/ce/CEAdapterSpec.scala`

```scala
package luau.ce

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import luau.core.fake.FakeBinding
import luau.scheduler.Scheduler

class CEAdapterSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers:

  val fakeState: Resource[IO, LuauCEState[IO]] =
    LuauCEState.resource[IO](
      IO(new FakeBinding),
      binding => new Scheduler(binding)
    )

  "CERef" - {
    "acquires and releases the underlying Ref" in { /* ... */ }
  }

  "CENativeFn" - {
    "lifted IO effect completes and resumes Task" in { /* ... */ }
    "lifted IO effect fails and propagates via LuaRuntimeException" in { /* ... */ }
    "fiber cancellation triggers Cancel" in { /* ... */ }
    "nested Suspend returns Internal error" in { /* ... */ }
  }

  "CELuauScript.run" - {
    "simple script returns decoded value" in { /* ... */ }
    "script error surfaces as LuaRuntimeException(LuaError.Script)" in { /* ... */ }
    "fiber cancellation cancels the Luau Task" in { /* ... */ }
  }
```

#### `zio/test/luau/zio/ZioIntegrationSpec.scala`

```scala
package luau.zio

import zio.*
import zio.test.*
// Uses a real backend (Panama on JVM, WASM on JS). Only run on CI with backend present.

object ZioIntegrationSpec extends ZIOSpecDefault:

  def spec = suite("ZioAdapter integration")(
    test("end-to-end: run Luau script with native ZIO fn, verify result") {
      // 1. Build LuauState with real backend.
      // 2. Register a Native fn that fires a ZIO.sleep(100.millis) then returns 42.
      // 3. Run Luau script: `return nativeFn()` expecting 42.
      // 4. Assert decoded result == 42.
      ZIO.scoped {
        for
          state  <- LuauState.scoped(realBinding, realScheduler)
          _      <- ZioSchedulerDriver.forkScoped(state.scheduler)
          result <- ZioLuauScript.run[Unit, Int](state, "return nativeFn()", "test", ())
        yield assertTrue(result == 42)
      }
    }
  )
```

#### `ce/test/luau/ce/CEIntegrationSpec.scala`

Mirrors `ZioIntegrationSpec` using `IO` and the CE resource API.

---

## 5. Acceptance Criteria and Tests

Every criterion below must be met before the plan is considered complete. The implementing agent should verify each one via the listed Mill task.

### 5.1 Unit Tests (fake backend, no FFI required)

| Test name | What it checks | Mill task |
|---|---|---|
| `ZioAdapter / scopedRef acquires and releases` | `Ref.close()` called exactly once on Scope exit | `./mill zio.jvm.test` |
| `ZioAdapter / liftNativeFn ZIO effect completes` | `resume(Right(...))` called, Task resumed | `./mill zio.jvm.test` |
| `ZioAdapter / liftNativeFn ZIO effect fails` | `resume(Left(LuaError))` called on ZIO failure | `./mill zio.jvm.test` |
| `ZioAdapter / liftNativeFn fiber interruption triggers Cancel` | Cancel lambda called when ZIO fiber interrupted | `./mill zio.jvm.test` |
| `ZioAdapter / runScript simple script` | Decoded `Int` matches Luau `return 42` | `./mill zio.jvm.test` |
| `ZioAdapter / runScript error` | `LuaError.Script` propagated | `./mill zio.jvm.test` |
| `ZioAdapter / runScript fiber interruption cancels Task` | `TaskHandle.cancel()` called on fiber interrupt | `./mill zio.jvm.test` |
| `ZioAdapter / nested Suspend returns Internal error` | `LuaError.Internal` when Suspend returned inside lift | `./mill zio.jvm.test` |
| `CEAdapter / (all CE equivalents)` | Same semantics via `cats.effect.IO` | `./mill ce.jvm.test` |

### 5.2 Integration Tests (real backend)

| Test name | What it checks | Mill task |
|---|---|---|
| `ZioIntegration / end-to-end ZIO native fn` | Luau script calls a ZIO-backed native fn, result decoded | `./mill zio.jvm.test` (tag: integration) |
| `CEIntegration / end-to-end CE native fn` | Same, via CE | `./mill ce.jvm.test` (tag: integration) |
| `ZioIntegration / Scoped state released on cancel` | Fiber cancel triggers state close, no Ref leaks | `./mill zio.jvm.test` (tag: integration) |
| `CEIntegration / Resource released on error` | CE Resource bracket fires on IO error | `./mill ce.jvm.test` (tag: integration) |

### 5.3 Scala.js Tests (WASM backend)

| Test name | Mill task |
|---|---|
| `ZioAdapter / (all unit tests on JS)` | `./mill zio.js.test` |
| `CEAdapter / (all unit tests on JS)` | `./mill ce.js.test` |

### 5.4 End-to-End Compilation Check

```sh
./mill zio.jvm.compile
./mill zio.js.compile
./mill ce.jvm.compile
./mill ce.js.compile
```

All four must succeed with zero errors. No warnings suppressed by `@nowarn` unless explicitly documented.

### 5.5 Leak Detector Check

If P03's dev-mode Ref leak detector is enabled, run integration tests with it active and assert zero open Refs after each test's Resource/Scope exits.

---

## 6. Risks and Gotchas

### 6.1 Driver-Thread Push Problem (Critical)

**Risk:** ZIO fibers and CE fibers run on their own thread pools. Pushing a Luau value (`binding.push[A](a)`) is only safe on the Driver. If `liftSimple` or `liftNativeFn` naively calls `binding.push` from the fiber thread, it races with other Driver operations and corrupts the Luau stack.

**Mitigation:** The `Result` type from P03 must carry a pre-encoded payload that the Scheduler pushes after `lua_resume`. If `Result` only carries an `Int` count of already-pushed values, the push must happen in a "Driver callback" submitted to the Run queue alongside the resume notification. Coordinate with P03/P06 authors before implementing `liftSimple`.

**Reference:** The Async primitive description in ADR-0007 says "`resume` only enqueues onto the Run queue; it never resumes inline." This means the actual stack push happens when the Scheduler dequeues and calls `lua_resume` on the Driver — which is correct. The `Result` carrying pre-encoded data is therefore the right design.

### 6.2 Double Resume (Critical)

**Risk:** ADR-0007 states "`resume` is one-shot; a second call is a dev-mode throw." A ZIO fiber that catches an error in `handleError` AND in `foldCauseZIO` could call `resume` twice if both paths fire. The same risk exists in CE with multiple error handlers.

**Mitigation:** Use `AtomicBoolean` or ZIO's `Promise` / CE's `Deferred` to guarantee one-shot. The lifted effect must call `resume` in exactly one code path.

### 6.3 Dispatcher Lifecycle (CE)

**Risk:** The `Dispatcher[F]` must be alive when `register` fires. If the `LuauCEState.resource` is released before all in-flight Native function calls complete, `dispatcher.unsafeRunAndForget` may throw or silently drop calls.

**Mitigation:** The Dispatcher must outlive any active Tasks. The `LuauCEState.resource` finalizer must shut down the Scheduler (draining/cancelling all Tasks) **before** releasing the Dispatcher. The `Resource` acquisition order in `LuauCEState.resource` must reflect this: acquire Dispatcher first, release Dispatcher last (stack discipline).

**Reference:** `/home/hoangdinh/OSS/luau-scala/docs/adr/0005-deterministic-ref-lifetime-no-finalizer.md` — the same deterministic-release principle applies to the Dispatcher.

### 6.4 ZIO Runtime Capture

**Risk:** `ZIO.asyncInterrupt` requires a `Runtime[R]` to launch fibers from a non-ZIO context (the `register` body called on the Driver). Using `Runtime.default` may not carry the correct environment `R`.

**Mitigation:** `LuauState` should carry a `Runtime[R]` or the adapter factory should accept one explicitly. The idiomatic ZIO pattern is to use `ZIO.runtime[R]` inside a ZIO effect to capture the runtime, then pass it to Native function registrations. Do not hardcode `Runtime.default`.

### 6.5 JS Event Loop Blocking

**Risk:** On Scala.js, `ZIO.blocking` and `Sync[F].blocking` may not behave the same as on the JVM — there is no thread pool to off-load to. Running the Scheduler drain loop with `ZIO.blocking` on JS could starve the event loop.

**Mitigation:** Platform-split the driver implementation: `zio/jvm/ZioSchedulerDriver.scala` uses `ZIO.blocking`; `zio/js/ZioSchedulerDriver.scala` uses `ZIO.yieldNow` between drains (or `ZIO.async` wrapping a `setImmediate`/`setTimeout(0)` call). The CE driver splits similarly.

### 6.6 Cancellation Race in ZIO asyncInterrupt

**Risk:** The Cancel lambda returned by `ZIO.asyncInterrupt` may be called before the fiber has been forked (i.e., `fiber` reference is still null in the `lift` implementation).

**Mitigation:** Use a `Promise[Nothing, Fiber[LuaError, NativeFnResult]]` to pass the fiber reference from the fork to the cancel. The cancel suspends on the Promise and then interrupts. Alternatively, use `ZIO.asyncInterrupt` at the top level (which handles this race internally) rather than the manual `fork` + `AtomicBoolean` pattern sketched in Section 4.1.

### 6.7 Scala.js and ZIO / CE Compatibility

**Risk:** Not all ZIO / CE features work identically on Scala.js. `ZIO.blocking` on JS is a no-op. `Dispatcher.parallel` on JS may behave unexpectedly.

**Mitigation:** Consult the ZIO and Cats Effect Scala.js compatibility matrices. Provide JS-specific givens where needed. Test the JS modules in CI against the Node.js target.

### 6.8 Nested Suspend

**Risk:** A ZIO effect returned from `liftNativeFn` that itself returns `CoreSuspend` as its value would create a nested suspension — a `Suspend` inside a `Suspend`. The Scheduler does not support this and would corrupt the Task.

**Mitigation:** The `lift` implementation must detect `CoreSuspend` in the fiber result and convert it to `LuaError.Internal`. This check is present in the skeleton above. Document clearly that `lift` only supports `Return` and `Fail` as fiber return values.

---

## 7. Out of Scope / Deferred

The following items are **deliberately excluded** from this plan. Do not implement them here; they belong to later plans or are outside project scope.

| Item | Reason / Owner |
|---|---|
| Multi-`Isolate` fan-out (running scripts in parallel across multiple states) | ADR-0002 defers multi-core parallelism. When implemented, it belongs to a P09+ plan. |
| `task.spawn` / `task.wait` wired to ZIO fibers or CE fibers | The Task library (P07) owns the Roblox task API. These adapters expose scripts as effects; they do not re-implement `task.*`. |
| ZIO `Schedule`-based retry or `ZStream`-based multi-value yield | Not part of the core API. Can be layered on top in user code. |
| CE `fs2.Stream` emitting Luau coroutine yields | Deferred; requires the Coroutine substrate (P07) and a streaming result protocol. |
| ZIO Metrics / Tracing integration | Deferred; not a core requirement. |
| Direct `Ref` to ZIO `Ref[A]` two-way sync | Out of scope; Refs are Luau-owned, decoding is explicit (ADR-0006). |
| Full `LuauEncoder` / `LuauDecoder` derivation for effect result types | P03 owns Codec. These adapters use whatever Codec instances P03 provides. |
| `Dispatcher.sequential` vs `Dispatcher.parallel` platform auto-selection | Deferred to a platform-specific given layer; the plan provides the pattern, not the automation. |

---

## 8. References

The implementing agent must read the following before writing any code:

### Architecture Decision Records (read in order)
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0001-embed-upstream-luau-via-slim-cpp-shim.md` — no protected calls across boundary
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0002-movable-state-actor-concurrency.md` — off-Driver completions enqueue, never resume inline
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0003-stackless-task-model.md` — Tasks are heap data, safe to migrate
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0004-coroutine-substrate-task-on-top.md` — Scheduler does not interpret yield payloads
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0005-deterministic-ref-lifetime-no-finalizer.md` — Refs must be closed explicitly; Resource/Scoped are the idiomatic owners
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0006-copy-only-data-boundary-via-codec-typeclass.md` — all data crosses by copy via Codec
- `/home/hoangdinh/OSS/luau-scala/docs/adr/0007-callback-based-async-and-tristate-native-return.md` — the Async primitive; resume is one-shot and thread-safe; Cancel fires on teardown

### Glossary (read in full)
- `/home/hoangdinh/OSS/luau-scala/CONTEXT.md` — canonical definitions for: Binding backend, Async primitive, Native function, Resume boundary, Ref, Scope, Suspension, Driver, Run queue, Task, Scheduler

### Dependency Plans (read for consumed types)
- `docs/plans/01-project-scaffold-and-build-toolchain.md` — Mill module layout, version catalog
- `docs/plans/03-core-abstractions.md` — all `core` types consumed by these adapters
- `docs/plans/06-scheduler-and-task-model.md` — `Scheduler`, `TaskHandle`, `RunQueue`; the threading contract

### Research (read for context; not binding unless cited above)
- `/home/hoangdinh/OSS/luau-scala/docs/research/runtime-luau-rust-ecosystem.md` — mluau's `asyncInterrupt` approach (Section 4.5) provides a parallel design pattern for bridging Rust async to Luau continuations; the same problem space applies here

### CONTEXT.md Terms Used in This Plan
The following CONTEXT.md terms appear in this plan with their canonical meanings; do not substitute synonyms:
- **Async primitive** — the `Suspend(register: Resume => Cancel)` bedrock
- **Resume boundary** — the only sanctioned Luau entry point; all Luau code runs inside `lua_resume`
- **Driver** — the execution context that owns a Luau state at any instant; exactly one Driver holds a state
- **Run queue** — the concurrent queue; completions enqueue here, never resume inline
- **Ref** — a stable Host-held handle to a Luau-heap object; must be closed explicitly
- **Scope** — a confined region that owns and closes Refs on exit
- **Native function** — a Scala function exposed to scripts via the Shim trampoline
- **Task** — a Coroutine the Scheduler owns and drives
- **Scheduler** — the Host loop that drains the Run queue
- **Codec** — `LuauEncoder[A]` / `LuauDecoder[A]` typeclass pair; data crosses by copy only
- **Binding backend** — the platform-specific Scala code that calls the Shim's ABI
