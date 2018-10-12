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
import cats.effect.concurrent.MVar
import cats.implicits._

import java.io._
import java.net._

/** Similar to [[EchoServerV1_Simple]], with an added feature: if a client sends 'STOP' then the server is shut down.
 */
object EchoServerV2_GracefulStop extends IOApp {

  def echoProtocol(clientSocket: Socket, stopFlag: MVar[IO, Unit]): IO[Unit] = {
  
    def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[IO, Unit]): IO[Unit] =
      for {
        line <- IO(reader.readLine())
        _    <- line match {
                  case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns IO[Unit] which is handy as we are done
                  case ""     => IO.unit          // Empty line, we are done
                  case _      => IO{ writer.write(line); writer.newLine(); writer.flush() } >> loop(reader, writer, stopFlag)
                }
      } yield ()
  
    def reader(clientSocket: Socket): Resource[IO, BufferedReader] =
      Resource.make {
        IO( new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) )
      } { reader =>
        IO(reader.close()).handleErrorWith(_ => IO.unit)
      }
  
    def writer(clientSocket: Socket): Resource[IO, BufferedWriter] =
      Resource.make {
        IO( new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())) )
      } { writer =>
        IO(writer.close()).handleErrorWith(_ => IO.unit)
      }

    def readerWriter(clientSocket: Socket): Resource[IO, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSocket)
        writer <- writer(clientSocket)
      } yield (reader, writer)

    readerWriter(clientSocket).use { case (reader, writer) =>
      loop(reader, writer, stopFlag) // Let's get to work
    }

  }

  def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = {

    def close(socket: Socket): IO[Unit] = 
      IO(socket.close()).handleErrorWith(_ => IO.unit)

    for {
      socketE <- IO(serverSocket.accept()).attempt
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
            _       <- if(!isEmpty) IO.unit    // stopFlag is set, nothing to do
                       else IO.raiseError(e)   // stopFlag not set, must raise error
          } yield ()
      }
    } yield ()
  }

  def server(serverSocket: ServerSocket): IO[ExitCode] =
    for {
      stopFlag    <- MVar[IO].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start // Server runs on its own Fiber
      _           <- stopFlag.read                       // Blocked until 'stopFlag.put(())' is run
      _           <- serverFiber.cancel                  // Stopping server!
    } yield ExitCode.Success

  override def run(args: List[String]): IO[ExitCode] = {

    def close(socket: ServerSocket): IO[Unit] =
      IO(socket.close()).handleErrorWith(_ => IO.unit)

    IO( new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) )
      .bracket {
        serverSocket => server(serverSocket)
      } {
        serverSocket => close(serverSocket)  >> IO(println("Server finished"))
      }
  }
}
