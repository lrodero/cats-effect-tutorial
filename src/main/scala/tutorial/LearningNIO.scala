package tutorial

import cats.effect._
import cats.implicits._

import java.nio.channels._
import java.net._
import java.util.concurrent.{Future => JFuture}

import scala.concurrent.{Future, Promise}
import scala.util._

object LearningNIO extends IOApp {

  def futureToIO[A](jf: JFuture[A]): IO[A] =
    IO.async[A] { (cb: Either[Throwable, A] => Unit) =>
      Try{ jf.get() }.toEither match {
        case right @ Right(_) => cb(right)
        case left  @ Left(_)  => cb(left)
      }
    }

  // OPEN PROBLEMS HERE: 
  //  1- Size of buffer? let's assume 1KB
  //  2- How to transform from bytes to string?   https://stackoverflow.com/questions/17354891/java-bytebuffer-to-string
  //
  //  Must: 
  //   a) accumulate in buffer until -1 is returned by read() operation
  //   b) transform to String (?)
  def readLine(asyncSktCh: AsynchronousSocketChannel): IO[Line] = {



    asyncSktCh.read 

  }


  def attendNewClient(asyncSktCh: AsynchronousSocketChannel): IO[Unit] = IO.unit

  def serve(asyncSrvScktCh: AsynchronousServerSocketChannel): IO[Unit] = {

    /* Discarded by now...
    val completionHandler = new CompletionHandler[AsynchronousSocketChannel, Unit]{
      override def completed(ch: AsynchronousSocketChannel, void: Unit): Unit = ()
      override def failed(t: Throwable, void: Unit): Unit = ()
    }
    */

    def accept: IO[JFuture[AsynchronousSocketChannel]] =
      IO.fromEither{ Try(asyncSrvScktCh.accept()).toEither }

    for {
      fAsyncSktCh <- accept
      asyncSktCh  <- futureToIO(fAsyncSktCh)
      _ <- attendNewClient(asyncSktCh)
      _ <- serve(asyncSrvScktCh)
    } yield ()

  }

  override def run(args: List[String]): IO[ExitCode] = {

    def close(asyncSrvScktCh: AsynchronousServerSocketChannel): IO[Unit] =
      IO{ asyncSrvScktCh.close() }.handleErrorWith(_ => IO.unit)

    val port = args.headOption.map(_.toInt).getOrElse(5432)

    IO{ AsynchronousServerSocketChannel.open().bind(new InetSocketAddress(port)) }
      .bracket {
        asyncSrvScktCh => serve(asyncSrvScktCh) *> IO{ println("Server finished") } *> IO.pure(ExitCode.Success) 
      } {
        asyncSrvScktCh => close(asyncSrvScktCh) *> IO{ println("Server socket closed") }
      }
  
  }
}
