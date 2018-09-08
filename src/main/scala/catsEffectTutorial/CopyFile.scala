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

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.concurrent.Semaphore
import cats.implicits._ 
import java.io._ 

object CopyFile extends IOApp {

  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long, semph: Semaphore[IO]): IO[Long] =
    for {
      _      <- IO.cancelBoundary // Cancelable at each iteration
      _      <- semph.acquire
      amount <- IO{ origin.read(buffer, 0, buffer.size) }.handleErrorWith(err => semph.release *> IO.raiseError(err))
      total  <- if(amount > -1) IO { destination.write(buffer, 0, amount) }.handleErrorWith(err => semph.release *> IO.raiseError(err)) *> semph.release *> transmit(origin, destination, buffer, acc + amount, semph)
                else semph.release *> IO.pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
    } yield total // Returns the actual amount of bytes transmitted

  def transfer(origin: InputStream, destination: OutputStream, semph: Semaphore[IO]): IO[Long] =
    for {
      buffer <- IO{ new Array[Byte](1024 * 10) } // Allocated only when the IO is evaluated
      acc    <- transmit(origin, destination, buffer, 0L, semph)
    } yield acc

  def close(is: InputStream): IO[Unit] =
    IO{is.close()}.handleErrorWith(_ => IO.unit)

  def close(os: OutputStream): IO[Unit] =
    IO{os.close()}.handleErrorWith(_ => IO.unit)

  def copy(origin: File, destination: File): IO[Long] = {
    val inIO: IO[InputStream]   = IO{ new FileInputStream(origin) }
    val outIO:IO[OutputStream]  = IO{ new FileOutputStream(destination) }
    val semph:IO[Semaphore[IO]] = Semaphore[IO](1)

    (inIO, outIO, semph)                 // Stage 1: Getting resources 
      .tupled                            // From (IO[InputStream], IO[OutputStream], IO[Semaphore]) to IO[(InputStream, OutputStream, Semaphore)]
      .bracket{
        case (in, out, semph) =>         // Stage 2: Using resources (for copying data, in this case)
          transfer(in, out, semph)
      } {
        case (in, out, semph) =>         // Stage 3: Freeing resources
          for {
            _ <- semph.acquire
            _ <- (close(in), close(out))
                   .tupled               // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
            _ <- semph.release
          } yield ()
      }
  }

  // The 'main' function of IOApp //
  override def run(args: List[String]): IO[ExitCode] =
    for {
      _      <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
                else IO.unit
      orig   <- IO.pure(new File(args(0)))
      dest   <- IO.pure(new File(args(1)))
      copied <- copy(orig, dest)
      _      <- IO{ println(s"$copied bytes copied from ${orig.getPath} to ${dest.getPath}") }
    } yield ExitCode.Success

}
