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

  class AsynchronousSocketChannelClosed(asyncSktCh: AsynchronousSocketChannel) extends Exception(s"${asyncSktCh.getRemoteAddress} channel closed")

  def readLine(asyncSktCh: AsynchronousSocketChannel): IO[String] = {

    def read(buffer: ByteBuffer): IO[Int] =
      IO.async[Int]{ cb =>
        def handler = new CompletionHandler[Int, Unit] {
          override def completed(result: Int, void: Unit) = cb(Right(result))
          override def failed(t: Throwable, void: Unit) = cb(Left(t))
        }

        Try{ asyncSckCh.read(byteBuffer, (), handler) }.toEither match {
          case Right(_) => () // The handler already used the cb(Right(result)) call to notify success
          case Left(t) => cb(Left(t))
        }
      }

    /**
     * Returns a copy of the byte buffer, resized by the growth factor.
     * The returned byte buffer contains the same data than the original one,
     * position is set to zero and both limit and capacity are the size of the
     * array.
     * To be used if readLine returns nothing but the buffer is full
     */
    def resizeBuffer(bb: ByteBuffer, growthFactor: Double = 2.0) = {
      val arr = bb.array
      val newArr = new Array[Byte]((arr.length * growthFactor).toInt)
      Array.copy(arr, 0, newArr, 0, arr.length)
      ByteBuffer.wrap(newArr)
    }

    // USE ByteBuffer.compact() once a line has been read!
    def extractLine(buffer: ByteBuffer, bufferInit: Int, bufferEnd: Int): Option[String] = {
      val reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buffer.array(), bufferInit, bufferEnd)))
      val lineO = Option(reader.readLine)
      reader.close
      lineO
    }

    def readLoop(buffer: ByteBuffer): IO[String] =
      for {
        initPos  <- IO.pure(buffer.position) // position in buffer where read will start from
        amntRead <- read(buffer) // Read data and write it onto buffer
        -        <- if(amntRead == -1) IO.raiseError(new AsynchronousSocketChannelClosed(asyncSckCh)) // Socket channel closed, nothing could be read
                    else IO.unit
        lineO = extractLine(buffer, initPos, buffer.position) // in case you are curious buffer.position should be the same as initPos + amntRead
        line <- lineO match {
                  case None => if(buffer.position == buffer.capacity) {  // buffer got full!! must read again on a bigger buffer that keeps the original data
                                 val biggerBuffer = resizeBuffer(buffer)
                                 biggerBuffer.position(buffer.position)
                                 readLoop(biggerBuffer)
                               } else { // Simply, read operation did not got a full line... must read again on the same buffer
                                 readLoop(buffer)
                               }
                  case Some(l) => IO.pure(l)
                }
      } yield line

    readLoop(ByteBuffer.allocate(1024)
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
