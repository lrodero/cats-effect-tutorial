package catsEffectTutorial

import cats.effect._
import cats.effect.concurrent._
import cats.effect.Clock._ // extractFromTimer
import cats.implicits._

import scala.concurrent.duration._
import scala.util.Right

import java.io._

object AsyncTest extends IOApp {

  def printCurrentThread(): Unit = println(s"Thread > ${Thread.currentThread().getName}")

  def millisecondsSince(clock: Clock[IO], t0: Long): IO[Long] =
    clock.realTime(MILLISECONDS).map(_ - t0)

  override def run(args: List[String]): IO[ExitCode] =
    for {
      clock <- IO(extractFromTimer[IO])
      t0 <- clock.realTime(MILLISECONDS)
      _ <- IO(print("In main loop (at 0) ")) *> IO(printCurrentThread())
      _ <- IO.async[Unit]{ cb =>
          print("STARTING ASYNC ")
          printCurrentThread()
          Thread.sleep(4000)
          cb(Right{print("ASYNC DONE "); printCurrentThread()})
        }
      t1 <- millisecondsSince(clock, t0)
      _ <- IO(print(s"In main loop before sleep (at $t1) ")) *> IO(printCurrentThread())
      _ <- IO.sleep(3.second)
      t2 <- millisecondsSince(clock, t0)
      _ <- IO(print(s"In main loop after sleep (at $t2)  ")) *> IO(printCurrentThread())
    } yield ExitCode.Success

}

object CancelTest extends IOApp {

  def ioLoop(interval: FiniteDuration): IO[Unit] =
    for {
      _ <- IO{println("hello")}
      _ <- IO.sleep(interval)
      _ <- ioLoop(interval)
    } yield ()

  def cancelableIO(interval: FiniteDuration) = Semaphore[IO](1)
    .bracket{ semph => 
      ioLoop(1.second) *>
      IO{println("bye")}
    } { semph =>
      IO{println("cancelled")}
    }

  def cancellerIO(fiber: Fiber[IO, Unit], t: FiniteDuration): IO[Unit] =
    for {
      _ <- IO.sleep(t)
      _ <- IO{println("cancelling")}
      _ <- fiber.cancel
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    for {
      fiber <- cancelableIO(1.second).start
      _     <- cancellerIO(fiber, 4.seconds)
    } yield ExitCode.Success

}

object ResourceTest extends IOApp {

  def openToRead(f: File): Resource[IO, BufferedReader] = {
    Resource.make{
      IO{println("Opening reader")} *> IO{new BufferedReader(new FileReader(f))}
    } { reader =>
      IO{println("Closing reader")} *> IO{reader.close()}
    }
  }

  def openToWrite(f: File): Resource[IO, BufferedWriter] = {
    Resource.make{
      IO{println("Opening writer")} *> IO{new BufferedWriter(new FileWriter(f))}
    } { writer =>
      IO{println("Closing writer")} *> IO{writer.close()}
    }
  }

  def open(fIn: File, fOut: File): Resource[IO, (BufferedReader, BufferedWriter)] =
    for {
      in  <- openToRead(fIn)
      out <- openToWrite(fOut)
    } yield (in, out)

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _    <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
                else IO.unit
      orig <- IO.pure(new File(args(0)))
      dest <- IO.pure(new File(args(1)))
      _    <- open(orig, dest).use { case(in, out) =>
                IO{println("Got in and out")}
              }
    } yield ExitCode.Success
}
