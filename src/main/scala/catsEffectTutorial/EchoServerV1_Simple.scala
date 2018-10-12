/*
 * Copyright (c) 2018 Luis Rodero-Merino
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at.
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
import cats.implicits._

import java.io._
import java.net._

/** Server that listens on a given port, and for each new connected client it spawns a new fiber to attend it. This
 *  fiber will just send back any line sent by the client ('echo'). When a client sends an empty line, the connection
 *  with that client is closed.
 */
object EchoServerV1_Simple extends IOApp {

  def echoProtocol(clientSocket: Socket): IO[Unit] = {
  
    def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = for {
      line <- IO(reader.readLine())
      _    <- line match {
                case "" => IO.unit // Empty line, we are done
                case _  => IO{ writer.write(line); writer.newLine(); writer.flush() } >> loop(reader, writer)
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
      loop(reader, writer) // Let's get to work
    }

  }

  def serve(serverSocket: ServerSocket): IO[Unit] = {
    def close(socket: Socket): IO[Unit] = 
      IO(socket.close()).handleErrorWith(_ => IO.unit)
  
    for {
      socket <- IO(serverSocket.accept())
      _      <- echoProtocol(socket)
                  .guarantee(close(socket)) // We close the socket whatever happens
                  .start                    // Client attended by its own Fiber!
      _      <- serve(serverSocket)         // Looping back to the beginning
    } yield ()
  }

  override def run(args: List[String]): IO[ExitCode] = {
  
    def close(socket: ServerSocket): IO[Unit] =
      IO(socket.close()).handleErrorWith(_ => IO.unit)
  
    IO( new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) )
      .bracket{
        serverSocket => serve(serverSocket) >> IO.pure(ExitCode.Success)
      } {
        serverSocket => close(serverSocket) >> IO(println("Server finished"))
      }
  }
}
