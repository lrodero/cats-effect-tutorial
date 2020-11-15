/*
 * Copyright 2020 Typelevel
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

package catseffecttutorial.producerconsumer.exerciseconcurrentqueue

import cats.effect.{Concurrent, ExitCode, IO, IOApp}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.syntax.all._
import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A purely functional, concurrent data structure which allows insertion and
 * retrieval of elements of type `A` in a first-in-first-out (FIFO) manner.
 *
 * Depending on the type of queue constructed, the [[Queue#offer]] operation can
 * block semantically until sufficient capacity in the queue becomes available.
 *
 * The [[Queue#take]] operation semantically blocks when the queue is empty.
 *
 * The [[Queue#tryOffer]] and [[Queue#tryTake]] allow for usecases which want to
 * avoid semantically blocking a fiber.
 *
 * This queue implementation is identical to
 * [[https://github.com/typelevel/cats-effect/blob/series/3.x/std/shared/src/main/scala/cats/effect/std/Queue.scala CE3's Queue]],
 * with the minimal modifications to run on CE2._
 */
abstract class Queue[F[_], A] { self =>

  /**
   * Enqueues the given element at the back of the queue, possibly semantically
   * blocking until sufficient capacity becomes available.
   *
   * @param a the element to be put at the back of the queue
   */
  def offer(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element at the back of the queue without
   * semantically blocking.
   *
   * @param a the element to be put at the back of the queue
   * @return an effect that describes whether the enqueuing of the given
   *         element succeeded without blocking
   */
  def tryOffer(a: A): F[Boolean]

  /**
   * Dequeues an element from the front of the queue, possibly semantically
   * blocking until an element becomes available.
   */
  def take: F[A]

  /**
   * Attempts to dequeue an element from the front of the queue, if one is
   * available without semantically blocking.
   *
   * @return an effect that describes whether the dequeueing of an element from
   *         the queue succeeded without blocking, with `None` denoting that no
   *         element was available
   */
  def tryTake: F[Option[A]]

}

object Queue {

  /**
   * Constructs an empty, bounded queue holding up to `capacity` elements for
   * `F` data types that are [[Concurrent]]. When the queue is full (contains
   * exactly `capacity` elements), every next [[Queue#offer]] will be
   * backpressured (i.e. the [[Queue#offer]] blocks semantically).
   *
   * @param capacity the maximum capacity of the queue
   * @return an empty, bounded queue
   */
  def bounded[F[_], A](capacity: Int)(implicit F: Concurrent[F]): F[Queue[F, A]] = {
    assertNonNegative(capacity)
    Ref.of[F, State[F, A]](State.empty[F, A]).map(new BoundedQueue(capacity, _))
  }

  /**
   * Constructs a queue through which a single element can pass only in the case
   * when there are at least one taking fiber and at least one offering fiber
   * for `F` data types that are [[Concurrent]]. Both [[Queue#offer]] and
   * [[Queue#take]] semantically block until there is a fiber executing the
   * opposite action, at which point both fibers are freed.
   *
   * @return a synchronous queue
   */
  def synchronous[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    bounded(0)

  /**
   * Constructs an empty, unbounded queue for `F` data types that are
   * [[Concurrent]]. [[Queue#offer]] never blocks semantically, as there is
   * always spare capacity in the queue.
   *
   * @return an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: Concurrent[F]): F[Queue[F, A]] =
    bounded(Int.MaxValue)

  private def assertNonNegative(capacity: Int): Unit =
    require(capacity >= 0, s"Bounded queue capacity must be non-negative, was: $capacity")

  private sealed abstract class AbstractQueue[F[_], A](
                                                        capacity: Int,
                                                        state: Ref[F, State[F, A]]
                                                      )(implicit F: Concurrent[F])
    extends Queue[F, A] {

    def offer(a: A): F[Unit] =
      Deferred[F,Unit].flatMap { offerer =>
        val cleanup = state.update { s => s.copy(offerers = s.offerers.filter(_._2 ne offerer)) }
        state.modify {
          case State(queue, size, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, size, rest, offerers) -> taker.complete(a).void

          case State(queue, size, takers, offerers) if size < capacity =>
            State(queue.enqueue(a), size + 1, takers, offerers) -> F.unit

          case State(queue, size, takers, offerers) => // Neither taker present nor capacity available in queue, 'blocking' call
            State(queue, size, takers, offerers.enqueue(a -> offerer)) -> offerer.get
        }.flatten.onCancel(cleanup)
      }

    def tryOffer(a: A): F[Boolean] =
      state
        .modify {
          case State(queue, size, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, size, rest, offerers) -> taker.complete(a).as(true)

          case State(queue, size, takers, offerers) if size < capacity =>
            State(queue.enqueue(a), size + 1, takers, offerers) -> F.pure(true)

          case s =>
            s -> F.pure(false)
        }
        .flatten
        .uncancelable

    val take: F[A] =
      Deferred[F, A].flatMap { taker =>
        val cleanup = state.update { s => s.copy(takers = s.takers.filter(_ ne taker)) }
        state.modify {  // cancellation inside modify has not effect
          case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
            val (a, rest) = queue.dequeue
            State(rest, size - 1, takers, offerers) -> F.pure(a)

          case State(queue, size, takers, offerers) if queue.nonEmpty =>
            val (a, rest) = queue.dequeue
            val ((move, release), tail) = offerers.dequeue
            State(rest.enqueue(move), size, takers, tail) -> release.complete(()).as(a)

          case State(queue, size, takers, offerers) if offerers.nonEmpty =>
            val ((a, release), rest) = offerers.dequeue
            State(queue, size, takers, rest) -> release.complete(()).as(a)

          case State(queue, size, takers, offerers) => // No offerer present and queue empty, 'blocking' call
            State(queue, size, takers.enqueue(taker), offerers) -> taker.get
        }.flatten.onCancel(cleanup)
      }

    val tryTake: F[Option[A]] =
      state
        .modify {
          case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
            val (a, rest) = queue.dequeue
            State(rest, size - 1, takers, offerers) -> F.pure(a.some)

          case State(queue, size, takers, offerers) if queue.nonEmpty =>
            val (a, rest) = queue.dequeue
            val ((move, release), tail) = offerers.dequeue
            State(rest.enqueue(move), size, takers, tail) -> release.complete(()).as(a.some)

          case State(queue, size, takers, offerers) if offerers.nonEmpty =>
            val ((a, release), rest) = offerers.dequeue
            State(queue, size, takers, rest) -> release.complete(()).as(a.some)

          case s =>
            s -> F.pure(none[A])
        }
        .flatten
        .uncancelable
  }

  private final class BoundedQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
    implicit F: Concurrent[F]
  ) extends AbstractQueue(capacity, state) {

  }

  private final case class State[F[_], A](
                                           queue: ScalaQueue[A],
                                           size: Int,
                                           takers: ScalaQueue[Deferred[F, A]],
                                           offerers: ScalaQueue[(A, Deferred[F, Unit])]
                                         )

  private object State {
    def empty[F[_], A]: State[F, A] =
      State(ScalaQueue.empty, 0, ScalaQueue.empty, ScalaQueue.empty)
  }
}

object QueueMain extends IOApp {

  def producer(id: Int, counterR: Ref[IO, Int], queue: Queue[IO, Int]): IO[Unit] =
    (for {
      i <- counterR.getAndUpdate(_ + 1)
      _ <- queue.offer(i)
      _ <- if(i % 10000 == 0) IO(println(s"Producer $id has reached $i items")) else IO.unit
      _ <- IO.shift
    } yield ()) >> producer(id, counterR, queue)

  def consumer(id: Int, queue: Queue[IO, Int]): IO[Unit] =
    (for {
      i <- queue.take
      _ <- if(i % 10000 == 0) IO(println(s"Consumer $id has reached $i items")) else IO.unit
      _ <- IO.shift
    } yield ()) >> consumer(id, queue)


  override def run(args: List[String]): IO[ExitCode] =
    for {
      queue <- Queue.unbounded[IO, Int]
      counterR <- Ref.of[IO, Int](1)
      producers = List.range(1, 11).map(producer(_, counterR, queue)) // 10 producers
      consumers = List.range(1, 11).map(consumer(_, queue))           // 10 consumers
      res <- (producers ++ consumers)
        .parSequence.as(ExitCode.Success) // Run producers and consumers in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          IO(println(s"Error caught: ${t.getMessage}")).as(ExitCode.Error)
        }
    } yield res
}
