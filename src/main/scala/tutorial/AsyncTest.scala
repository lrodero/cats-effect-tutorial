package tutorial

import cats.effect._
import cats.effect.Clock._ // extractFromTimer
import cats.implicits._

import scala.concurrent.duration._
import scala.util.Right

object AsyncTest extends IOApp {

  def printCurrentThread(): Unit = println(s"Thread > ${Thread.currentThread().getName}")

  def millisecondsSince(clock: Clock[IO], t0: Long): IO[Long] =
    clock.realTime(MILLISECONDS).map(_ - t0)

  override def run(args: List[String]): IO[ExitCode] = {


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

}
