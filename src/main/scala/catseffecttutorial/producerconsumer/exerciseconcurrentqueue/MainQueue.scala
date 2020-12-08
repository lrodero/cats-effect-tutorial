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

import cats.effect.std.Queue
import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.syntax.all._

object MainQueue extends IOApp {

  def producer(id: Int, counterR: Ref[IO, Int], queue: Queue[IO, Int]): IO[Unit] =
    (for {
      i <- counterR.getAndUpdate(_ + 1)
      _ <- queue.offer(i)
      _ <- if(i % 10000 == 0) IO(println(s"Producer $id has reached $i items")) else IO.unit
    } yield ()) >> producer(id, counterR, queue)

  def consumer(id: Int, queue: Queue[IO, Int]): IO[Unit] =
    (for {
      i <- queue.take
      _ <- if(i % 10000 == 0) IO(println(s"Consumer $id has reached $i items")) else IO.unit
    } yield ()) >> consumer(id, queue)


  override def run(args: List[String]): IO[ExitCode] =
    for {
      queue <- Queue.bounded[IO, Int](100)
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
