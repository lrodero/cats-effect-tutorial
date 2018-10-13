/*
 * Copyright (c) 2018 Luis Rodero-Merino
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
package catsEffectTutorial

import cats.effect._
import cats.effect.concurrent.Semaphore
import cats.implicits._ 
import java.io._ 

object PolymorphicCopyFile extends IOApp {

  def transmit[F[_]: Concurrent](origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): F[Long] =
    for {
      amount <- Concurrent[F].delay(origin.read(buffer, 0, buffer.size))
      count  <- if(amount > -1) Concurrent[F].delay(destination.write(buffer, 0, amount)) >> transmit(origin, destination, buffer, acc + amount)
                else Concurrent[F].pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
    } yield count // Returns the actual amount of bytes transmitted

  def transfer[F[_]: Concurrent](origin: InputStream, destination: OutputStream): F[Long] =
    for {
      buffer <- Concurrent[F].delay( new Array[Byte](1024 * 10) ) // Allocated only when F is evaluated
      total  <- transmit(origin, destination, buffer, 0L)
    } yield total

  def inputStream[F[_]: Concurrent](f: File, guard: Semaphore[F]): Resource[F, FileInputStream] =
    Resource.make {
      Concurrent[F].delay(new FileInputStream(f))
    } { inStream => 
      guard.withPermit {
        Concurrent[F].delay(inStream.close()).handleErrorWith(_ => Concurrent[F].unit)
      }
    }

  def outputStream[F[_]: Concurrent](f: File, guard: Semaphore[F]): Resource[F, FileOutputStream] =
    Resource.make {
      Concurrent[F].delay(new FileOutputStream(f))
    } { outStream =>
      guard.withPermit {
        Concurrent[F].delay(outStream.close()).handleErrorWith(_ => Concurrent[F].unit)
      }
    }

  def inputOutputStreams[F[_]: Concurrent](in: File, out: File, guard: Semaphore[F]): Resource[F, (InputStream, OutputStream)] =
    for {
      inStream  <- inputStream(in, guard)
      outStream <- outputStream(out, guard)
    } yield (inStream, outStream)

  def copy[F[_]: Concurrent](origin: File, destination: File): F[Long] = 
    for {
      guard <- Semaphore[F](1)
      count <- inputOutputStreams(origin, destination, guard).use { case (in, out) => 
                 guard.withPermit(transfer(in, out))
               }
    } yield count

  // The 'main' function of IOApp //
  override def run(args: List[String]): IO[ExitCode] =
    for {
      _      <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
                else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      count <- copy[IO](orig, dest)
      _     <- IO(println(s"$count bytes copied from ${orig.getPath} to ${dest.getPath}"))
    } yield ExitCode.Success

}
