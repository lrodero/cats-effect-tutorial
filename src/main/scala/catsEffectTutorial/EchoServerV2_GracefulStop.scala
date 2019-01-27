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
import cats.effect.ExitCase._
import cats.effect.syntax.all._
import cats.effect.concurrent.MVar
import cats.implicits._

import java.io._
import java.net._

/** Similar to [[EchoServerV1_Simple]], with an added feature: if a client sends 'STOP' then the server is shut down.
 */
object EchoServerV2_GracefulStop extends IOApp {

  def echoProtocol[F[_]: Sync](clientSocket: Socket, stopFlag: MVar[F, Unit]): F[Unit] = {
  
    def loop(reader: BufferedReader, writer: BufferedWriter, stopFlag: MVar[F, Unit]): F[Unit] =
      for {
        line <- Sync[F].delay(reader.readLine())
        _    <- line match {
                  case "STOP" => stopFlag.put(()) // Stopping server! Also put(()) returns F[Unit] which is handy as we are done
                  case ""     => Sync[F].unit     // Empty line, we are done
                  case _      => Sync[F].delay{ writer.write(line); writer.newLine(); writer.flush() } >> loop(reader, writer, stopFlag)
                }
      } yield ()
  
    def reader(clientSocket: Socket): Resource[F, BufferedReader] =
      Resource.make {
        Sync[F].delay( new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) )
      } { reader =>
        Sync[F].delay(reader.close()).handleErrorWith(_ => Sync[F].unit)
      }
  
    def writer(clientSocket: Socket): Resource[F, BufferedWriter] =
      Resource.make {
        Sync[F].delay( new BufferedWriter(new PrintWriter(clientSocket.getOutputStream())) )
      } { writer =>
        Sync[F].delay(writer.close()).handleErrorWith(_ => Sync[F].unit)
      }

    def readerWriter(clientSocket: Socket): Resource[F, (BufferedReader, BufferedWriter)] =
      for {
        reader <- reader(clientSocket)
        writer <- writer(clientSocket)
      } yield (reader, writer)

    readerWriter(clientSocket).use { case (reader, writer) =>
      loop(reader, writer, stopFlag) // Let's get to work
    }

  }

  def serve[F[_]: Concurrent](serverSocket: ServerSocket, stopFlag: MVar[F, Unit]): F[Unit] = {

    def close(socket: Socket): F[Unit] = 
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)
    
    for {
      _ <- Sync[F]
             .delay(serverSocket.accept())
             .bracketCase { socket =>
               echoProtocol(socket, stopFlag)
                 .guarantee(close(socket))                 // Ensuring socket is closed
                 .start                                    // Client attended by its own Fiber
             }{ (socket, exit) => exit match {
               case Completed => Sync[F].unit
               case Error(_) | Canceled => close(socket)
             }}
      _ <- serve(serverSocket, stopFlag)                   // Looping back to the beginning
    } yield ()

  }

  def server[F[_]: Concurrent](serverSocket: ServerSocket): F[ExitCode] =
    for {
      stopFlag    <- MVar[F].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start // Server runs on its own Fiber
      _           <- stopFlag.read                       // Blocked until 'stopFlag.put(())' is run
      _           <- serverFiber.cancel.start            // Stopping server!
    } yield ExitCode.Success

  override def run(args: List[String]): IO[ExitCode] = {
  
    def close[F[_]: Sync](socket: ServerSocket): F[Unit] =
      Sync[F].delay(socket.close()).handleErrorWith(_ => Sync[F].unit)

    IO( new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) )
      .bracket{
        serverSocket => server[IO](serverSocket) >> IO.pure(ExitCode.Success)
      } {
        serverSocket => close[IO](serverSocket)  >> IO(println("Server finished"))
      }
  }
}
