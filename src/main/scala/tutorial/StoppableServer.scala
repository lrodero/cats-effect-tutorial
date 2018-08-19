package tutorial

import cats.effect._
import cats.effect.concurrent.MVar
import cats.implicits._

import java.io._
import java.net._

object StoppableServer extends IOApp {

  def echoProtocol(clientSocket: Socket, stopFlag: MVar[IO, Unit]): IO[Unit] = {
  
    def close(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = 
      (IO{reader.close()}, IO{writer.close()})
        .tupled                        // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
        .map(_ => ())                  // From IO[(Unit, Unit)] to IO[Unit]
        .handleErrorWith(_ => IO.unit) // Swallowing up any possible error
  
    def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] =
      for {
        _    <- IO.cancelBoundary
        line <- IO{ reader.readLine() }
        _    <- line match {
                  case "STOP" => stopFlag.put(()) // Returns IO[Unit], which is handy as we are done here
                  case ""     => IO.unit          // Empty line, we are done
                  case _      => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
                }
      } yield ()
  
    val readerIO = IO{ new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) }
    val writerIO = IO{ new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())) }
  
    (readerIO, writerIO)
      .tupled       // From (IO[BufferedReader], IO[BufferedWriter]) to IO[(BufferedReader, BufferedWriter)]
      .bracket {
        case (reader, writer) => loop(reader, writer)  // Let's get to work!
      } {
        case (reader, writer) => close(reader, writer) // We are done, closing the streams
      }
  }

  def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = {

    def close(socket: Socket): IO[Unit] = 
      IO{ socket.close() }.handleErrorWith(_ => IO.unit)

    for {
      _       <- IO.cancelBoundary
      socketE <- IO{ serverSocket.accept() }.attempt
      _       <- socketE match {
        case Right(socket) =>
          for { // accept() succeeded, we attend the client in its own Fiber
            _ <- echoProtocol(socket, stopFlag)
                   .guarantee(close(socket))   // We close the server whatever happens
                   .start                      // Client attended by its own Fiber
            _ <- serve(serverSocket, stopFlag) // Looping back to the beginning
          } yield ()
        case Left(e) =>
          for { // accept() failed, stopFlag will tell us whether this is a graceful shutdown
            isEmpty <- stopFlag.isEmpty
            _ <- if(!isEmpty) IO.unit // stopFlag is set, nothing to do
            else IO.raiseError(e)     // stopFlag not set, must raise error
          } yield ()
      }
    } yield ()
  }

  def server(serverSocket: ServerSocket): IO[ExitCode] =
    for {
      stopFlag    <- MVar[IO].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start
      _           <- stopFlag.read
      _           <- serverFiber.cancel
    } yield ExitCode.Success

  override def run(args: List[String]): IO[ExitCode] = {

    def close(socket: ServerSocket): IO[Unit] =
      IO{ socket.close() }.handleErrorWith(_ => IO.unit)

    IO{ new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
      .bracket {
        serverSocket => server(serverSocket) *> IO.pure(ExitCode.Success)
      } {
        serverSocket => close(serverSocket)  *> IO{ println("Server finished") }
      }
  }
}
