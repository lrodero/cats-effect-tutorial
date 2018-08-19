package tutorial

import cats.effect._
import cats.implicits._

import java.io._
import java.net._

object Server extends IOApp {

  def echoProtocol(clientSocket: Socket): IO[Unit] = {
  
    def close(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = 
      (IO{reader.close()}, IO{writer.close()})
        .tupled                        // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
        .map(_ => ())                  // From IO[(Unit, Unit)] IO[Unit]
        .handleErrorWith(_ => IO.unit) // Swallowing up any possible error
  
    def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = for {
      _    <- IO.cancelBoundary
      line <- IO{ reader.readLine() }
      _    <- line match {
                case "" => IO.unit // Empty line, we are done
                case _  => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
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

  def serve(serverSocket: ServerSocket): IO[Unit] = {
    def close(socket: Socket): IO[Unit] = 
      IO{ socket.close() }.handleErrorWith(_ => IO.unit)
  
    for {
      _      <- IO.cancelBoundary
      socket <- IO{ serverSocket.accept() }
      _      <- echoProtocol(socket)
                  .guarantee(close(socket)) // We close the socket whatever happens
                  .start                    // Client attended by its own Fiber!
      _      <- serve(serverSocket)         // Looping back to the beginning
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = {
  
    def close(socket: ServerSocket): IO[Unit] =
      IO{ socket.close() }.handleErrorWith(_ => IO.unit)
  
    IO{ new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
      .bracket{
        serverSocket => serve(serverSocket) *> IO.pure(ExitCode.Success)
      } {
        serverSocket => close(serverSocket) *> IO{ println("Server finished") }
      }
  }
}
