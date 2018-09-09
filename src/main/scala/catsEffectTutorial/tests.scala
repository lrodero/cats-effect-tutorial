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
      _ <- IO{println("looping!")}
      _ <- ioLoop(interval)
    } yield ()

  def cancelableIO(interval: FiniteDuration) = Semaphore[IO](1)
    .bracket{ semph => 
      ioLoop(interval) *>
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
      fiber <- cancelableIO(3.second).start
      _     <- cancellerIO(fiber, 4.seconds)
    } yield ExitCode.Success


}

object ResourceTest extends IOApp {

  def reader(f: File): Resource[IO, BufferedReader] =
    Resource.make{ 
      IO(println(s"Opening ${f.getPath} for reading")) *> IO(new BufferedReader(new FileReader(f)))
    }{ reader =>
      IO(println(s"Closing reader to ${f.getPath}")) *> IO(reader.close())
    }

  def writer(f: File): Resource[IO, BufferedWriter] =
    Resource.make{ 
      IO(println(s"Opening ${f.getPath} for writing")) *> IO(new BufferedWriter(new FileWriter(f)))
    }{ writer =>
      IO(println(s"Closing writer to ${f.getPath}")) *> IO(writer.close())
    }

  def readerWriter(in: File, out:File): Resource[IO, (BufferedReader, BufferedWriter)] =
    for {
      reader <- reader(in)
      writer <- writer(out)
    } yield (reader, writer)

  def use(reader: BufferedReader, writer: BufferedWriter): IO[Unit] =
    IO.unit

  override def run(args: List[String]): IO[ExitCode] = 
    for {
      _ <- readerWriter(new File(args(0)), new File(args(1))).use {
          case (reader, writer) => use(reader, writer)
        }
    } yield ExitCode.Success

}


object BreakOnAcquire extends IOApp {


  def niceAcquire: IO[Unit] = IO(println("Acquiring and then being cool")) *> IO.unit
  def brokenAcquire: IO[Unit] = IO(println("Acquiring and then breaking")) *> IO.raiseError(new Error("ups"))

  def break: IO[Unit] =
    (niceAcquire, brokenAcquire)
      .tupled
      .bracket{ _ =>
      IO(println("On usage stage")) *> IO.unit
    } { _ =>
      IO(println("On release stage")) *> IO.unit
    }

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- break
      _ <- IO(println("after breaking"))
    } yield ExitCode.Success

}

object BreakOnResource extends IOApp {
  def reader(f: File): Resource[IO, BufferedReader] =
    Resource.make{ 
      IO(println(s"Opening ${f.getPath} for reading")) *> IO(new BufferedReader(new FileReader(f)))
    }{ reader =>
      IO(println(s"Closing reader to ${f.getPath}")) *> IO(reader.close())
    }

  def writer(f: File): Resource[IO, BufferedWriter] =
    Resource.make{ 
      IO(println(s"Opening ${f.getPath} for writign")) *> IO(new BufferedWriter(new FileWriter(f)))
    }{ writer =>
      IO(println(s"Closing writer to ${f.getPath}")) *> IO(writer.close())
    }

  def readerWriter(in: File, out:File): Resource[IO, (BufferedReader, BufferedWriter)] =
    for {
      reader <- reader(in)
      writer <- writer(out)
    } yield (reader, writer)

  def use(reader: BufferedReader, writer: BufferedWriter): IO[Unit] =
    IO.raiseError(new Error("ups"))

  override def run(args: List[String]): IO[ExitCode] = 
    for {
      _ <- readerWriter(new File(args(0)), new File(args(1))).use {
          case (reader, writer) => use(reader, writer)
        }
    } yield ExitCode.Success
}
