/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.internals

import cats.effect.IO
import cats.effect.IO.{Async, Bind, ContextSwitch, Delay, Introspect, Map, Pure, RaiseError, Suspend, Trace}
import cats.effect.tracing.TracingMode
import cats.effect.internals.TracingPlatformFast.tracingEnabled

import scala.util.control.NonFatal

private[effect] object IORunLoop {
  private type Current = IO[Any]
  private type Bind = Any => IO[Any]
  private type CallStack = ArrayStack[Bind]
  // TODO: replace with a mutable ring buffer
  private type Callback = Either[Throwable, Any] => Unit

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  def start[A](source: IO[A], cb: Either[Throwable, A] => Unit): Unit = {
    if (tracingEnabled) {
      IOTracing.setLocalTracingMode(TracingMode.Disabled)
    }
    loop(source, IOConnection.uncancelable, cb.asInstanceOf[Callback], null, null, null, null)
  }

  def restart[A](source: IO[A], ctx: IOContext, mode: TracingMode, cb: Either[Throwable, A] => Unit): Unit = {
    if (tracingEnabled) {
      IOTracing.setLocalTracingMode(mode)
    }
    loop(source, IOConnection.uncancelable, cb.asInstanceOf[Callback], ctx, null, null, null)
  }

  /**
   * Evaluates the given `IO` reference, calling the given callback
   * with the result when completed.
   */
  def startCancelable[A](source: IO[A], conn: IOConnection, cb: Either[Throwable, A] => Unit): Unit = {
    if (tracingEnabled) {
      IOTracing.setLocalTracingMode(TracingMode.Disabled)
    }
    loop(source, conn, cb.asInstanceOf[Callback], null, null, null, null)
  }

  def restartCancelable[A](source: IO[A], conn: IOConnection, ctx: IOContext, mode: TracingMode, cb: Either[Throwable, A] => Unit): Unit = {
    if (tracingEnabled) {
      IOTracing.setLocalTracingMode(mode)
    }
    loop(source, conn, cb.asInstanceOf[Callback], ctx, null, null, null)
  }

  /**
   * Loop for evaluating an `IO` value.
   *
   * The `rcbRef`, `bFirstRef` and `bRestRef`  parameters are
   * nullable values that can be supplied because the loop needs
   * to be resumed in [[RestartCallback]].
   */
  private def loop(
    source: Current,
    cancelable: IOConnection,
    cb: Either[Throwable, Any] => Unit,
    ctxRef: IOContext,
    rcbRef: RestartCallback,
    bFirstRef: Bind,
    bRestRef: CallStack
  ): Unit = {
    var currentIO: Current = source
    // Can change on a context switch
    var conn: IOConnection = cancelable
    var ctx: IOContext = ctxRef
    var bFirst: Bind = bFirstRef
    var bRest: CallStack = bRestRef
    var rcb: RestartCallback = rcbRef
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null
    // For auto-cancellation
    var currentIndex = 0

    while ({
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
//          if (tracingEnabled) {
//            if (ctx eq null) ctx = IOContext()
//            ctx.pushFrame(bind.trace)
//          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch {
            case NonFatal(e) =>
              currentIO = RaiseError(e)
          }

        case Suspend(thunk) =>
          currentIO =
            try thunk()
            catch { case NonFatal(ex) => RaiseError(ex) }

        case RaiseError(ex) =>
          findErrorHandler(bFirst, bRest) match {
            case null =>
              cb(Left(ex))
              return
            case bind =>
              val fa =
                try bind.recover(ex)
                catch { case NonFatal(e) => RaiseError(e) }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
//          if (tracingEnabled) {
//            if (ctx eq null) ctx = IOContext()
//            ctx.pushFrame(bindNext.trace)
//          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case async @ Async(_, _) =>
          if (conn eq null) conn = IOConnection()
          // We need to initialize an IOContext because the continuation
          // may produce trace frames.
          if (ctx eq null) ctx = IOContext()
          if (rcb eq null) rcb = new RestartCallback(conn, ctx, cb.asInstanceOf[Callback])
          rcb.start(async, bFirst, bRest)
          return

        case ContextSwitch(next, modify, restore) =>
          val old = if (conn ne null) conn else IOConnection()
          conn = modify(old)
          currentIO = next
          if (conn ne old) {
            if (rcb ne null) rcb.contextSwitch(conn)
            if (restore ne null)
              currentIO = Bind(next, new RestoreContext(old, restore))
          }

        case Trace(source, frame) =>
          if (ctx eq null) ctx = IOContext()
          ctx.pushFrame(frame)
          currentIO = source

        case Introspect =>
          if (ctx eq null) ctx = IOContext()
          hasUnboxed = true
          unboxed = ctx.getTrace

      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            cb(Right(unboxed))
            return
          case bind =>
            val fa =
              try bind(unboxed)
              catch { case NonFatal(ex) => RaiseError(ex) }
            hasUnboxed = false
            unboxed = null
            bFirst = null
            currentIO = fa
        }
      }

      // Auto-cancellation logic
      currentIndex += 1
      if (currentIndex == maxAutoCancelableBatchSize) {
        if (conn.isCanceled) return
        currentIndex = 0
      }
      true
    }) ()
  }

  /**
   * Evaluates the given `IO` reference until an asynchronous
   * boundary is hit.
   */
  def step[A](source: IO[A]): IO[A] = {
    var currentIO: Current = source
    var bFirst: Bind = null
    var bRest: CallStack = null
    var ctx: IOContext = null
    // Values from Pure and Delay are unboxed in this var,
    // for code reuse between Pure and Delay
    var hasUnboxed: Boolean = false
    var unboxed: AnyRef = null

    while ({
      currentIO match {
        case Bind(fa, bindNext) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Pure(value) =>
          unboxed = value.asInstanceOf[AnyRef]
          hasUnboxed = true

        case Delay(thunk) =>
          try {
            unboxed = thunk().asInstanceOf[AnyRef]
            hasUnboxed = true
            currentIO = null
          } catch {
            case NonFatal(e) =>
              currentIO = RaiseError(e)
          }

        case Suspend(thunk) =>
          currentIO =
            try {
              thunk()
            } catch { case NonFatal(ex) => RaiseError(ex) }

        case RaiseError(ex) =>
          findErrorHandler(bFirst, bRest) match {
            case null =>
              return currentIO.asInstanceOf[IO[A]]
            case bind =>
              val fa =
                try bind.recover(ex)
                catch { case NonFatal(e) => RaiseError(e) }
              bFirst = null
              currentIO = fa
          }

        case bindNext @ Map(fa, _, _) =>
          if (bFirst ne null) {
            if (bRest eq null) bRest = new ArrayStack()
            bRest.push(bFirst)
          }
          bFirst = bindNext.asInstanceOf[Bind]
          currentIO = fa

        case Async(_, _) =>
          // Cannot inline the code of this method — as it would
          // box those vars in scala.runtime.ObjectRef!
          return suspendAsync(currentIO.asInstanceOf[IO.Async[A]], bFirst, bRest)

        case Trace(source, frame) =>
          if (ctx eq null) ctx = IOContext()
          ctx.pushFrame(frame)
          currentIO = source

        case Introspect =>
          // TODO: This can be implemented in terms of Async now
          if (ctx eq null) ctx = IOContext()
          hasUnboxed = true
          unboxed = ctx.getTrace

        case _ =>
          return Async { (conn, ctx, cb) =>
            loop(currentIO, conn, cb.asInstanceOf[Callback], ctx, null, bFirst, bRest)
          }
      }

      if (hasUnboxed) {
        popNextBind(bFirst, bRest) match {
          case null =>
            return (if (currentIO ne null) currentIO else Pure(unboxed))
              .asInstanceOf[IO[A]]
          case bind =>
            currentIO =
              try bind(unboxed)
              catch { case NonFatal(ex) => RaiseError(ex) }
            hasUnboxed = false
            unboxed = null
            bFirst = null
        }
      }
      true
    }) ()
    // $COVERAGE-OFF$
    null // Unreachable code
    // $COVERAGE-ON$
  }

  private def suspendAsync[A](currentIO: IO.Async[A], bFirst: Bind, bRest: CallStack): IO[A] =
    // Hitting an async boundary means we have to stop, however
    // if we had previous `flatMap` operations then we need to resume
    // the loop with the collected stack
    if (bFirst != null || (bRest != null && !bRest.isEmpty))
      Async { (conn, ctx, cb) =>
        loop(currentIO, conn, cb.asInstanceOf[Callback], ctx, null, bFirst, bRest)
      }
    else
      currentIO

  /**
   * Pops the next bind function from the stack, but filters out
   * `IOFrame.ErrorHandler` references, because we know they won't do
   * anything — an optimization for `handleError`.
   */
  private def popNextBind(bFirst: Bind, bRest: CallStack): Bind = {
    if ((bFirst ne null) && !bFirst.isInstanceOf[IOFrame.ErrorHandler[_]])
      return bFirst

    if (bRest eq null) return null
    while ({
      val next = bRest.pop()
      if (next eq null) {
        return null
      } else if (!next.isInstanceOf[IOFrame.ErrorHandler[_]]) {
        return next
      }
      true
    }) ()
    // $COVERAGE-OFF$
    null
    // $COVERAGE-ON$
  }

  /**
   * Finds a [[IOFrame]] capable of handling errors in our bind
   * call-stack, invoked after a `RaiseError` is observed.
   */
  private def findErrorHandler(bFirst: Bind, bRest: CallStack): IOFrame[Any, IO[Any]] =
    bFirst match {
      case ref: IOFrame[Any, IO[Any]] @unchecked => ref
      case _ =>
        if (bRest eq null) null
        else {
          while ({
            val ref = bRest.pop()
            if (ref eq null)
              return null
            else if (ref.isInstanceOf[IOFrame[_, _]])
              return ref.asInstanceOf[IOFrame[Any, IO[Any]]]
            true
          }) ()
          // $COVERAGE-OFF$
          null
          // $COVERAGE-ON$
        }
    }

  /**
   * A `RestartCallback` gets created only once, per [[startCancelable]]
   * (`unsafeRunAsync`) invocation, once an `Async` state is hit,
   * its job being to resume the loop after the boundary, but with
   * the bind call-stack restored
   *
   * This is a trick the implementation is using to avoid creating
   * extraneous callback references on asynchronous boundaries, in
   * order to reduce memory pressure.
   *
   * It's an ugly, mutable implementation.
   * For internal use only, here be dragons!
   */
  final private class RestartCallback(connInit: IOConnection, ctx: IOContext, cb: Callback)
      extends Callback
      with Runnable {
    import TrampolineEC.{immediate => ec}

    // can change on a ContextSwitch
    private[this] var conn: IOConnection = connInit
    private[this] var canCall = false
    private[this] var trampolineAfter = false
    private[this] var bFirst: Bind = _
    private[this] var bRest: CallStack = _
    private[this] var tMode: TracingMode = _

    // Used in combination with trampolineAfter = true
    private[this] var value: Either[Throwable, Any] = _

    def contextSwitch(conn: IOConnection): Unit =
      this.conn = conn

    def start(task: IO.Async[Any], bFirst: Bind, bRest: CallStack): Unit = {
      canCall = true
      this.bFirst = bFirst
      this.bRest = bRest
      this.trampolineAfter = task.trampolineAfter
      if (tracingEnabled) {
        this.tMode = IOTracing.getLocalTracingMode()
      }

      // Go, go, go
      task.k(conn, ctx, this)
    }

    private[this] def signal(either: Either[Throwable, Any]): Unit = {
      // Allow GC to collect
      val bFirst = this.bFirst
      val bRest = this.bRest
      this.bFirst = null
      this.bRest = null

      if (tracingEnabled) {
        // The continuation may have been invoked on a new execution context,
        // so let's recover the tracing mode here.
        IOTracing.setLocalTracingMode(this.tMode)
        this.tMode = null
      }

      // Auto-cancelable logic: in case the connection was cancelled,
      // we interrupt the bind continuation
      if (!conn.isCanceled) either match {
        case Right(success) =>
          loop(Pure(success), conn, cb, ctx, this, bFirst, bRest)
        case Left(e) =>
          loop(RaiseError(e), conn, cb, ctx, this, bFirst, bRest)
      }
    }

    override def run(): Unit = {
      // N.B. this has to be set to null *before* the signal
      // otherwise a race condition can happen ;-)
      val v = value
      value = null
      signal(v)
    }

    def apply(either: Either[Throwable, Any]): Unit =
      if (canCall) {
        canCall = false
        if (trampolineAfter) {
          this.value = either
          ec.execute(this)
        } else {
          signal(either)
        }
      }
  }

  final private class RestoreContext(
    old: IOConnection,
    restore: (Any, Throwable, IOConnection, IOConnection) => IOConnection
  ) extends IOFrame[Any, IO[Any]] {
    def apply(a: Any): IO[Any] =
      ContextSwitch(Pure(a), current => restore(a, null, old, current), null)
    def recover(e: Throwable): IO[Any] =
      ContextSwitch(RaiseError(e), current => restore(null, e, old, current), null)
  }

  /**
   * Number of iterations before the connection is checked for its
   * cancelled status, to interrupt synchronous flatMap loops.
   */
  private[this] val maxAutoCancelableBatchSize = 512
}
