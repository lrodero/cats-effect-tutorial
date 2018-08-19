package tutorial

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._ 

import java.io._ 

object Main extends IOApp {

  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte]): IO[Int] =
    for {
      amount <- IO{ origin.read(buffer, 0, buffer.size) }
      _      <- if(amount > -1) IO { destination.write(buffer, 0, amount) }
                else IO.unit // End of read stream reached (by java.io.InputStream contract), nothing to write
    } yield amount // Returns the actual amount of bytes transmitted

  def transmitLoop(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
    for {
      _      <- IO.cancelBoundary                     // Cancelable at each iteration
      amount <- transmit(origin, destination, buffer) // Make the actual transfer
      total  <- if(amount > -1) transmitLoop(origin, destination, buffer, acc + amount) // Stack safe!
                else IO.pure(acc)                     // Negative 'amount' signals end of input stream
    } yield total

  def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
    for {
      buffer <- IO{ new Array[Byte](1024 * 10) } // Allocated only when the IO is evaluated
      acc    <- transmitLoop(origin, destination, buffer, 0L)
    } yield acc

  def copy(origin: File, destination: File): IO[Long] = {
    val in: IO[InputStream]  = IO{ new BufferedInputStream(new FileInputStream(origin)) }
    val out:IO[OutputStream] = IO{ new BufferedOutputStream(new FileOutputStream(destination)) }

    (in, out)                  // Stage 1: Getting resources 
      .tupled                  // From (IO[InputStream], IO[OutputStream]) to IO[(InputStream, OutputStream)]
      .bracket{
        case (in, out) =>
          transfer(in, out)    // Stage 2: Using resources (for copying data, in this case)
      } {
        case (in, out) =>      // Stage 3: Freeing resources
          (IO{in.close()}, IO{out.close()})
          .tupled              // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
          .handleErrorWith(_ => IO.unit) *> IO.unit
      }
  }

  // The 'main' function of IOApp //
  override def run(args: List[String]): IO[ExitCode] =
    for {
      _      <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
                else IO.unit
      orig = new File(args(0))
      dest = new File(args(1))
      copied <- copy(orig, dest)
      _      <- IO{ println(s"$copied bytes copied from ${orig.getPath} to ${dest.getPath}") }
    } yield ExitCode.Success

}
