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

import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{ContextShift, ExitCode, IO, IOApp, Sync}
import cats.instances.list._
import cats.syntax.all._

import scala.collection.immutable.Queue

/**
 * Multiple producer - multiple consumer system using a bounded concurrent queue.
 *
 * Second part of cats-effect tutorial at https://typelevel.org/cats-effect/tutorial/tutorial.html
 */
object ProducerConsumerBounded extends IOApp {

  def producer[F[_]: Sync: ContextShift](id: Int, queueR: Ref[F, Queue[Int]], counterR: Ref[F, Int], empty: Semaphore[F], filled: Semaphore[F]): F[Unit] =
    (for {
      i <- counterR.getAndUpdate(_ + 1)
      _ <- empty.acquire // Wait for some empty bucket
      _ <- queueR.getAndUpdate(_.enqueue(i))
      _ <- filled.release // Signal new item in queue
      _ <- if(i % 10000 == 0) Sync[F].delay(println(s"Producer $id has reached $i items")) else Sync[F].unit
      _ <- ContextShift[F].shift
    } yield ()) >> producer(id, queueR, counterR, empty, filled)

  def consumer[F[_]: Sync: ContextShift](id: Int, queueR: Ref[F, Queue[Int]], empty: Semaphore[F], filled: Semaphore[F]): F[Unit] =
    (for {
      _ <- filled.acquire // Wait for some item in queue
      i <- queueR.modify(_.dequeue.swap)
      _ <- empty.release  // Signal new empty bucket
      _ <- if(i % 10000 == 0) Sync[F].delay(println(s"Consumer $id has reached $i items")) else Sync[F].unit
      _ <- ContextShift[F].shift
    } yield ()) >> consumer(id, queueR, empty, filled)

  override def run(args: List[String]): IO[ExitCode] =
    for {
      queueR <- Ref.of[IO, Queue[Int]](Queue.empty[Int])
      counterR <- Ref.of[IO, Int](1)
      empty <- Semaphore[IO](100)
      filled <- Semaphore[IO](0)
      producers = List.range(1, 11).map(producer(_, queueR, counterR, empty, filled)) // 10 producers
      consumers = List.range(1, 11).map(consumer(_, queueR, empty, filled))           // 10 consumers
      res <- (producers ++ consumers)
        .parSequence.as(ExitCode.Success) // Run producers and consumers in parallel until done (likely by user cancelling with CTRL-C)
        .handleErrorWith { t =>
          IO(println(s"Error caught: ${t.getMessage}")).as(ExitCode.Error)
        }
    } yield res
}
