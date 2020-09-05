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
package catseffecttutorial.producerconsumer.exerciseconcurrentqueue

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{Concurrent, ContextShift, ExitCode, IO, IOApp, Sync}
import cats.instances.list._
import cats.syntax.all._

import scala.collection.immutable.Queue

trait SimpleConcurrentQueue[F[_], A] {
  /** Get and remove first element from queue, blocks if queue emptyV. */
  def poll: F[A]
  /** Put element at then end of queue, blocks if queue is bounded and full. */
  def put(a: A): F[Unit]
}

object SimpleConcurrentQueue {

  protected def assertPositive[F[_]](n: Int)(implicit F: Sync[F]): F[Unit] =
    if(n <= 0) F.raiseError(new IllegalArgumentException(s"Argument must be > 0 but it is $n")) else F.unit

  def unbounded[F[_]: Concurrent: Sync, A]: F[SimpleConcurrentQueue[F, A]] =
    for {
      queueR <- Ref[F].of(Queue.empty[A])
      filled <- Semaphore[F](0)
    } yield buildUnbounded(queueR, filled)

  private def buildUnbounded[F[_], A](queueR: Ref[F, Queue[A]], filled: Semaphore[F])(implicit F: Sync[F]): SimpleConcurrentQueue[F, A] =
    new SimpleConcurrentQueue[F, A] {
      override def poll: F[A] =
        F.uncancelable(
          for {
            _ <- filled.acquire
            a <- queueR.modify(_.dequeue.swap)
          } yield a
        )

      override def put(a: A): F[Unit] =
        F.uncancelable(
          for {
            _ <- queueR.getAndUpdate(_.enqueue(a))
            _ <- filled.release
          } yield ()
        )
    }

  def bounded[F[_]: Concurrent: Sync, A](size: Int): F[SimpleConcurrentQueue[F, A]] =
    for {
      _ <- assertPositive(size)
      queueR <- Ref[F].of(Queue.empty[A])
      filled <- Semaphore[F](0)
      empty <- Semaphore[F](size)
    } yield buildBounded(queueR, filled, empty)

  def buildBounded[F[_], A](queueR: Ref[F, Queue[A]], filled: Semaphore[F], empty: Semaphore[F])(implicit F: Sync[F]): SimpleConcurrentQueue[F, A] =
    new SimpleConcurrentQueue[F, A] {
      override def poll: F[A] =
        F.uncancelable(
          for {
            _ <- filled.acquire
            a <- queueR.modify(_.dequeue.swap)
            _ <- empty.release
          } yield a
        )

      override def put(a: A): F[Unit] =
        F.uncancelable(
          for {
            _ <- empty.acquire
            _ <- queueR.getAndUpdate(_.enqueue(a))
            _ <- filled.release
          } yield ()
        )
    }
}

object SimpleConcurrentQueueRunner extends IOApp {

  def process[F[_]: Sync](i: Int): F[Unit] =
    if (i % 10000 == 0) Sync[F].delay(println(s"Processed $i elements"))
    else Sync[F].unit

  def producer[F[_]: Sync: ContextShift](counterR: Ref[F, Int], scq: SimpleConcurrentQueue[F, Int]): F[Unit] =
    (counterR.getAndUpdate(_ + 1) >>= scq.put) >> ContextShift[F].shift >> producer(counterR, scq)

  def consumer[F[_]: Sync: ContextShift](scq: SimpleConcurrentQueue[F, Int]): F[Unit] =
    (scq.poll >>= process[F]) >> ContextShift[F].shift >> consumer(scq)

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      scq <- SimpleConcurrentQueue.bounded[IO, Int](10000)
      counterR <- Ref.of[IO, Int](0)
      producers = List.range(1, 11).as(producer[IO](counterR, scq)) // 10 producers
      consumers = List.range(1, 11).as(consumer[IO](scq))           // 10 consumers
      res <- (producers ++ consumers)
        .parSequence.as(ExitCode.Success) // Run producers and consumers in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          IO(println(s"Error caught: ${t.getMessage}")).as(ExitCode.Error)
        }
    } yield res
  }
}
