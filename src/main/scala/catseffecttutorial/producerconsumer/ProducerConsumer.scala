/*
 * Copyright (c) 2020 Luis Rodero-Merino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at.
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package catseffecttutorial.producerconsumer

import cats.effect.{Async, Deferred, ExitCode, IO, IOApp, Ref, Sync}
import cats.instances.list._
import cats.syntax.all._

import scala.collection.immutable.Queue

/**
 * Multiple producer - multiple consumer system using an unbounded concurrent queue.
 *
 * Second part of cats-effect tutorial at https://typelevel.org/cats-effect/tutorial/tutorial.html
 *
 * Code to _offer_ and _take_ elements to/from queue is taken from CE3's Queue implementation.
 */
object ProducerConsumer extends IOApp {

  case class State[F[_], A](queue: Queue[A], takers: Queue[Deferred[F,A]])

  object State {
    def empty[F[_], A]: State[F, A] = State(Queue.empty, Queue.empty)
  }

  def producer[F[_]: Sync](id: Int, counterR: Ref[F, Int], stateR: Ref[F, State[F,Int]]): F[Unit] = {

    def offer(i: Int): F[Unit] =
      stateR.modify {
        case State(queue, takers) if takers.nonEmpty =>
          val (taker, rest) = takers.dequeue
          State(queue, rest) -> taker.complete(i).void
        case State(queue, takers) =>
          State(queue.enqueue(i), takers) -> Sync[F].unit
      }.flatten

    (for {
      i <- counterR.getAndUpdate(_ + 1)
      _ <- offer(i)
      _ <- if(i % 10000 == 0) Sync[F].delay(println(s"Producer $id has reached $i items")) else Sync[F].unit
    } yield ()) >> producer(id, counterR, stateR)
  }

  def consumer[F[_]: Async](id: Int, stateR: Ref[F, State[F, Int]]): F[Unit] = {

    val take: F[Int] =
      Deferred[F, Int].flatMap { taker =>
        stateR.modify {
          case State(queue, takers) if queue.nonEmpty =>
            val (i, rest) = queue.dequeue
            State(rest, takers) -> Async[F].pure(i)
          case State(queue, takers) =>
            State(queue, takers.enqueue(taker)) -> taker.get
        }.flatten
      }

    (for {
      i <- take
      _ <- if(i % 10000 == 0) Async[F].delay(println(s"Consumer $id has reached $i items")) else Async[F].unit
    } yield ()) >> consumer(id, stateR)
  }

  override def run(args: List[String]): IO[ExitCode] =
    for {
      stateR <- Ref.of[IO, State[IO,Int]](State.empty[IO, Int])
      counterR <- Ref.of[IO, Int](1)
      producers = List.range(1, 11).map(producer(_, counterR, stateR)) // 10 producers
      consumers = List.range(1, 11).map(consumer(_, stateR))           // 10 consumers
      res <- (producers ++ consumers)
        .parSequence.as(ExitCode.Success) // Run producers and consumers in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          IO(println(s"Error caught: ${t.getMessage}")).as(ExitCode.Error)
        }
    } yield res
}
