Cats-effect tutorial
====================
[Cats-effect](https://typelevel.org/cats-effect), the effects library for
[Cats](https://typelevel.org/cats), has a complete documentation explaining the
types it brings, full with small examples on how to use them. However, even
with that documentation available, it can be a bit daunting start using the
library for the first time.

This tutorial tries to close that gap by means of two examples. The first one
shows how to copy the contents from one file to another. That should help us to
flex our muscles. The second one, being still a small example, is fairly more
complex. It shows how to code a TCP server able to attend several clients at
the same time, each one being served by its own `Fiber`. In several iterations
we will create new versions of that server with addded functionality that will
require using more and more concepts of `cats-effect`.

Setting things up
-----------------
To easy coding the snippets in this tutorial it is recommended to use `sbt` as
the build tool. This is a possible `build.sbt` file for the project:

```scala
name := "cats-effect-tutorial"

version := "0.1"

scalaVersion := "2.12.2"

libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0-RC3"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
```

Copying contents of a file
--------------------------
First we will code a function that copies the content from a file to another
file. The function takes as parameters the source and destination files. But
this is functional programming! So invoking the function will not copy
anything, instead it returns an `IO` instance that encapsulates all the
side-effects involved (opening files, copying content, etc.), that way _purity_
is kept. Only when that `IO` instance is evaluated all those side-effects will
run. In our implementation the `IO` instance will return the amount of bytes
copied upon execution, but this is just a design decission. All this said, the
signature of our function looks like this:

```scala
import cats.effect.IO
import java.io.File

def copy(origin: File, destination: File): IO[Long] = ???
```

Nothing scary, uh? Now, let's go step-by-step to implement our function. First
thing to do, we need to open two streams that will read and write file
contents. We consider opening an stream to be a side-effect action, so we have
to encapsulate those actions in their own `IO` instances.

```scala
import cats.effect.IO
import java.io._


def copy(origin: File, destination: File): IO[Long] = {
  val inIO: IO[InputStream]  = IO{ new BufferedInputStream(new FileInputStream(origin)) }
  val outIO:IO[OutputStream] = IO{ new BufferedOutputStream(new FileOutputStream(destination)) }

  ???
}
```

We want to ensure that once we are done copying both streams are close. For
that we will use `bracket`. There are three stages when using `bracket`:
resource adquisition, usage, and release.  Thus we define our `copy` function
as follows:

```scala
import cats.effect.IO
import cats.implicits._ 
import java.io._ 

def transfer(origin: InputStream, destination: OutputStream): IO[Long] = ???

def copy(origin: File, destination: File): IO[Long] = {
  val inIO: IO[InputStream]  = IO{ new BufferedInputStream(new FileInputStream(origin)) }
  val outIO:IO[OutputStream] = IO{ new BufferedOutputStream(new FileOutputStream(destination)) }

  (inIO, outIO)              // Stage 1: Getting resources 
    .tupled                  // From (IO[InputStream], IO[OutputStream]) to IO[(InputStream, OutputStream)]
    .bracket{
      case (in, out) =>
        transfer(in, out)    // Stage 2: Using resources (for copying data, in this case)
    } {
      case (in, out) =>      // Stage 3: Freeing resources
        (IO{in.close()}, IO{out.close()})
        .tupled              // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
        .handleErrorWith(_ => IO.unit) *> IO.unit
    }
}
```

So far so good, we have our streams ready to go! And in a safe manner too, as
the `bracket` construct ensures they are closed no matter what. Note that any
possible error raised when closing is 'swallowed' by the `handleErrorWith`
call, which in the code above basically ignores the error cause. Not elegant,
but enough for this example. Anyway in the real world there would be little
else to do save maybe showing a warning message. Finally we chain the closing
action using `*>` call to return an `IO.unit` after closing (note that `*>` is
like using `flatMap` but the second step does not need as input the output from
the first).

By `bracket` contract the action happens in what we have called _Stage 2_,
where given the resources we must return an `IO` instance that perform the task
at hand.

For the sake of clarity, the actual construction of that `IO` will be done by a
different function `transfer`. That function will have to define a loop that at
each iteration reads data from the input stream into a buffer, and then writes
the buffer contents into the output. At the same time, the loop will keep a
counter of the bytes transferred. To reuse the same buffer we should define it
outside the main loop, and leave the actual transmission of data to another
function `transmit` that uses that loop. Something like:


```scala
import cats.effect.IO
import cats.implicits._ 
import java.io._ 

def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
  for {
    _      <- IO.cancelBoundary // Cancelable at each iteration
    amount <- IO{ origin.read(buffer, 0, buffer.size) }
    total  <- if(amount > -1) IO { destination.write(buffer, 0, amount) } *> transmit(origin, destination, buffer, acc + amount)
              else IO.pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
  } yield total // Returns the actual amount of bytes transmitted

def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
  for {
    buffer <- IO{ new Array[Byte](1024 * 10) } // Allocated only when the IO is evaluated
    acc    <- transmit(origin, destination, buffer, 0L)
  } yield acc
```
Take a look to `transmit`, observe that both input and output actions are
encapsulated in their own `IO` instances. Being `IO` a monad we concatenate them
using a for-comprehension to create another `IO`. The for-comprehension loops as
long as the call to `read()` does not return a negative value, by means of
recursive calls. But `IO` is stack safe, so we are not concerned about stack
overflow issues. At each iteration we increase the counter `acc` with the amount
of bytes read at that iteration.  Also, we introduce a call to
`IO.cancelBoundary` as the first step of the loop. This is not mandatory for the
actual transference of data we aim for. But it is a good policy, as it marks
where the `IO` evaluation will be stopped (canceled) if requested. In this case,
at the beginning of each iteration.

And that is it! We are done, now we can create a program that tests this
function. We will use `IOApp` for that, as it allows to maintain purity in our
definitions up to the main function. So our final code will look like:

```scala
package tutorial

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._ 
import java.io._ 

object CopyFile extends IOApp {

  def transmit(origin: InputStream, destination: OutputStream, buffer: Array[Byte], acc: Long): IO[Long] =
    for {
      _      <- IO.cancelBoundary // Cancelable at each iteration
      amount <- IO{ origin.read(buffer, 0, buffer.size) }
      total  <- if(amount > -1) IO { destination.write(buffer, 0, amount) } *> transmit(origin, destination, buffer, acc + amount)
                else IO.pure(acc) // End of read stream reached (by java.io.InputStream contract), nothing to write
    } yield total // Returns the actual amount of bytes transmitted

  def transfer(origin: InputStream, destination: OutputStream): IO[Long] =
    for {
      buffer <- IO{ new Array[Byte](1024 * 10) } // Allocated only when the IO is evaluated
      acc    <- transmit(origin, destination, buffer, 0L)
    } yield acc

  def copy(origin: File, destination: File): IO[Long] = {
    val inIO: IO[InputStream]  = IO{ new BufferedInputStream(new FileInputStream(origin)) }
    val outIO:IO[OutputStream] = IO{ new BufferedOutputStream(new FileOutputStream(destination)) }

    (inIO, outIO)              // Stage 1: Getting resources 
      .tupled                  // From (IO[InputStream], IO[OutputStream]) to IO[(InputStream, OutputStream)]
      .bracket{
        case (in, out) =>
          transfer(in, out)    // Stage 2: Using resources (for copying data, in this case)
      } {
        case (in, out) =>      // Stage 3: Freeing resources
          (IO{in.close()}, IO{out.close()})
          .tupled              // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
          .handleErrorWith(_ => IO.unit) *> IO.unit
      }
  }

  // The 'main' function of IOApp //
  override def run(args: List[String]): IO[ExitCode] =
    for {
      _      <- if(args.length < 2) IO.raiseError(new IllegalArgumentException("Need origin and destination files"))
                else IO.unit
      orig   <- IO.pure(new File(args(0)))
      dest   <- IO.pure(new File(args(1)))
      copied <- copy(orig, dest)
      _      <- IO{ println(s"$copied bytes copied from ${orig.getPath} to ${dest.getPath}") }
    } yield ExitCode.Success

}
```

`IOApp` is a kind of 'functional' equivalent to Scala's `App`, where instead of
coding an effectful `main` method we code a pure `run` function. When executing
the class a `main` method defined in `IOApp` will call the `run` function we
have coded.

Also, heed how `run` verifies the `args` list passed. If there are less than two
arguments, an error is raised. As `IO` implements `MonadError` we can at any
moment call to `IO.raiseError` to interrupt a sequence of `IO` operations.

You can run this code from `sbt` just by issuing this call:

```scala
> runMain tutorial.CopyFile
```

Exercises
---------
To finalize we propose you some exercises that will help you to keep improving
your IO-kungfu:

1. If the list of args has less than 2 elements an exception is thrown. This is
   a bit rough. Create instead an `evaluateArgs` function that checks the
   validity of the arguments given to the main function. This function will
   return an `Either[String, Unit]` instance. In case of error (that is, the
   list of args has less than two elements), the `Left[String]` will contain an
   error message, `Right[Unit]` will signal that the list of args is fine.  The
   function signature will thus be:

```scala
  def evaluateArgs(args: List[String]): Either[String, Unit] = ???
```

2. Include the `evaluateArgs` function defined above in the `run` function.
   When it returns `Left[String]` the err message will be shown to the user and
   then the program will finish gracefully. If it returbs `Right[Unit]` the
   execution will continue normally. Feel free to 'break' the for-comprehension
   in `run` in different parts if that helps you.
3. What happens if the user asks to copy a file that does not exist? You can
   check it yourself: a not-so-good-looking exception is thrown and shown to
   the user. Think and experiment mechanisms to capture such exceptions and
   show better-looking error messages.
4. Modify `transmit` so the buffer size is not hardcoded but passed as
   parameter. That parameter will be passed through `transmitLoop`, `transfer`
   and `copy` from the main `run` function. Modify the `run` and `evaluateArgs`
   functions so the buffer size can optionally be stated when calling to the
   program. `evaluateArgs` shall signal error if the third arg is present but
   it is not a valid number. `run` will use the third arg as buffer size if
   present, if not a default hardcode value will be passed to `copy`.

Echo server
-----------
This example scales up a bit on complexity. Here we create an echo TCP server
that simply replies to each text message from a client sending back that same
message. When the client sends an empty line the connection is shutdown by the
server.

This server will be able to attend several clients at the same time. For that we
will use `cats-effect`'s `Fiber`, which can be seen as light threads. For each
new client a `Fiber` instance will be spawned to serve that client.

A design guide we will stick to: whoever method creates a resource is the sole
responsible of dispatching it.

Let's build our server step-by-step. First we will code a method that implement
the echo protocol. It will take as input the socket (`java.net.Socket`
instance) that is connected to the client. The method will be basically a loop
that at each iteration reads the input from the client, if the input is not an
empty line then the text is sent back to the client, otherwise the method will
finish. The method signature will look like this:

```scala
import cats.effect.IO
import java.net.Socket
def echoProtocol(clientSocket: Socket): IO[Unit] = ???
```

Reading and writing will be done using `java.io.BufferedReader` and
`java.io.BufferedWriter` instances build from the socket. Recall that this
method will be in charge of closing those buffers, but not the client socket
(it did not create that socket after all!). So we can start coding the method
scaffolding:

```scala
import cats.effect._
import cats.implicits._
import java.io._
import java.net._

def echoProtocol(clientSocket: Socket): IO[Unit] = {

  def close(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = 
    (IO{reader.close()}, IO{writer.close()})
      .tupled                        // From (IO[Unit], IO[Unit]) to IO[(Unit, Unit)]
      .map(_ => ())                  // From IO[(Unit, Unit)] to IO[Unit]
      .handleErrorWith(_ => IO.unit) // Swallowing up any possible error

  def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = ???

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
```

Realize that we are using again `bracket` to ensure that the method closes the
two streams it creates.  Also, all actions with potential side-effects are
encapsulated in `IO` instances. That way we ensure no side-effect is actually
run until the `IO` returned by this method is evaluated. We could use as well
`bracketCase` for a finer control of what actually caused the termination:

```scala
import cats.effect.Exitcase._

(IO{ new BufferedReader(new InputStreamReader(clientSocket.getInputStream())) },
 IO{ new BufferedWriter(new PrintWriter(socket.getOutputStream()))) }
  .tupled       // From (IO[BufferedReader], IO[BufferedWriter]) to IO[(BufferedReader, BufferedWriter)]
  .bracketCase {
    case (reader, writer) => loop(reader, writer)
  } {
    case ((reader, writer), Completed)  => IO{ println("Finished service to client normally") } *> close(reader, writer)
    case ((reader, writer), Canceled)   => IO{ println("Finished service to client because cancellation") } *> close(reader, writer)
    case ((reader, writer), Error(err)) => IO{ println(s"Finished service to client due to error: '${err.getMessage}'") } *> close(reader, writer)
  }
```

That is a good policy and you must be aware that possibility exists.  But for
the sake of clarity we will stick to the simpler `bracket` for this tutorial.

Finally, and as we did in the previous example, we ignore possible errors when
closing the streams, as there is little to do in such cases.  But, of course,
we still miss that `loop` method that will do the actual interactions with the
client. It is not hard to code though:

```scala
def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] =
  for {
    _    <- IO.cancelBoundary
    line <- IO{ reader.readLine() }
    _    <- line match {
              case "" => IO.unit // Empty line, we are done
              case _  => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
            }
  } yield ()
```

The loop tries to read a line from the client, and if successful then it checks
the line content. If empty it finishes the method, if not it sends back the
line through the writer and loops back to the beginning. Easy, right :) ?

So we are done with our `echoProtocol` method, good! But we still miss the part
of our server that will list for new connections and create `Fiber`s to attend
them. Let's work on that, we implement that functionality in another method
that takes as input the `java.io.ServerSocket` instance that will listen for
clients:

```scala
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
```

To be honest, that wasn't that hard either, was it? We invoke the `accept()`
method of `ServerSocket` and when a new connection arrives we call the
`echoProtocol` method we defined above to attend it. As client socket instances
are created by this method, then it is in charge of closing them once
`echoProtocol` has finished. We do this with the quite handy `guarantee` call,
that ensures that when the `IO` finishes the functionality inside `guarantee`
is run whatever the outcome was. In this case we ensure closing the socket,
ignoring any possible error when closing. Also quite interesting: we use
`start`! By doing so the `echoProtocol` call will run on its own `Fiber` thus
not blocking the main loop. As in the previous example to copy files, we include
a call to `IO.cancelBoundary` so we ensure the loop can be cancelled at each
iteration. However let's not mix up this with Java thread `interrupt` or the
like. Calling to `cancel` on a `Fiber` instance will not stop it immediately. So
in the code above, if the fiber is waiting on `accept()` then `cancel()` would
not 'unlock' the fiber. Instead the fiber will keep waiting for a connection.
Only when the loop iterates again and the `cancelBoundary` is reached then the
fiber will be effectively canceled.

So, what do we miss now? Only the creation of the server socket of course,
which we can already do in the `run` method of an `IOApp`. So our final server
version will look like this:

```scala
package tutorial

import cats.effect._
import cats.implicits._

import java.io._
import java.net._

object SimpleServer extends IOApp {

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
```

As before you can run in for example from the `sbt` console just by typing
 
```scala
> runMain tutorial.SimpleServer
```

That will start the server on default port `5432`, you can also set any other
port by passing it as argument. To test the server is properly running, you can
connect to it using `telnet`. Here we connect, send `hi` line to check we get
the same line as reply, and then send and empty line to close the connection:

```console
$ telnet localhost 5432
Trying 127.0.0.1...
Connected to localhost.
Escape character is '^]'.
hi
hi

Connection closed by foreign host.
```

You can connect several telnet sessions at the same time to verify that indeed
our server can attend all of them simultaneously.

Unfortunately this server is a bit too simplistic. For example, how can we stop
it? Well, that is something we have not addressed yet and it is when things can
get a bit more complicated. We will deal with proper server halting in the next
section.

Handling exit events in echo server
-----------------------------------
There is no way to shutdown gracefully the echo server coded in the previous
version. Sure we can always `<Ctrl>-c` it, but proper servers should provide
better mechanisms to stop them. In this section we use some other `cats-effect`
constructs to deal with this.

First, we will use a flag to signal when the server shall quit. The server will
run on its own `Fiber`, that will be canceled when that flag is set. The flag
will be an instance of `MVar`. The `cats-effect` documentation describes `MVar`
as _a mutable location that can be empty or contains a value, asynchronously
blocking reads when empty and blocking writes when full_. That is what we need,
the hability to block! So we will 'block' by reading our `MVar` instance, and
only writing when `STOP` is received, the write being the _signal_ that the
server must be shut down. The server will be only stopped once, so we are not
concerned about blocking on writing. Another possible choice would be using
`cats-effect`'s `Deferred`, but unlike `Deferred` `MVar` does allow to peek
whether a value was written or not. As we shall see, this will come handy later
on.

////////////////////////////////////////////////
////////////////////////////////////////////////
////////////////////////////////////////////////
////////////////////////////////////////////////
////////////////////////////////////////////////
CONTINUA REVIEW POR AQUI
////////////////////////////////////////////////
////////////////////////////////////////////////
////////////////////////////////////////////////
////////////////////////////////////////////////
////////////////////////////////////////////////

And who shall signal that the server must be stopped? In this example, we will
assume that it will be the connected users who can request the server to halt by
sendind a `STOP` message. Thus, the method attending clients (`echoProtocol`!)
needs access to the flag.

Let's first define a new method `server` that instantiates the flag, runs the
`serve` method in its own `Fiber` and waits on the flag to be set. Only when
the flag is set the server fiber will be canceled.

```scala
def server(serverSocket: ServerSocket): IO[ExitCode] = 
  for {
      stopFlag    <- MVar[IO].empty[Unit]
      serverFiber <- serve(serverSocket, stopFlag).start // Server runs on its own Fiber
      _           <- stopFlag.read                       // Blocked until 'stopFlag.put(())' is run
      _           <- serverFiber.cancel                  // Stopping server!
  } yield ExitCode.Success
```

We also modify the main `run` method in `IOApp` so now it calls to `server`:

```scala
override def run(args: List[String]): IO[ExitCode] = {

  def close(socket: ServerSocket): IO[Unit] =
    IO{ socket.close() }.handleErrorWith(_ => IO.unit)

  IO{ new ServerSocket(args.headOption.map(_.toInt).getOrElse(5432)) }
    .bracket{
      serverSocket => server(serverSocket) *> IO.pure(ExitCode.Success)
    } {
      serverSocket => close(serverSocket)  *> IO{ println("Server finished") }
    }
}
```

So `run` calls `server` which in turn calls `serve`. Do we need to modify
`serve` as well? Yes, for two reasons:

1. We need to pass the `stopFlag` to the `echoProtocol` method.
2. When the `server` method returns the `run` method will close the server
   socket. That will cause the `serverSocket.accept()` in `serve` to throw an
   exception. We could handle it as any other exception... but that would show
   an error message in console, while in fact this is a 'controlled' shutdown.
   So we should instead control what caused the exception.

This is how we implement `serve` now:

```scala
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
          _       <- if(!isEmpty) IO.unit    // stopFlag is set, nothing to do
                     else IO.raiseError(e)   // stopFlag not set, must raise error
        } yield ()
    }
  } yield ()
}
```

This new implementation of `serve` does not just call `accept()` inside an
`IO`. It also uses `attempt`, which returns an instance of `Either`
(`Either[Throwable, Socket]` in this case). We can then check the value of that
`Either` to verify if the `accept()` call was successful, and in case of error
deciding what to do depending on whether the `stopFlag` is set or not.

There is only one step missing, modifying `echoLoop`. The only relevant changes
are two: modifying the signature to pass the `stopFlag` flag; and in the `loop`
function checking whether the line from the client equals `STOP`, in such case
the flag we will set and the function will be finished. The final code looks as
follows:

```scala
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
            _       <- if(!isEmpty) IO.unit    // stopFlag is set, nothing to do
                       else IO.raiseError(e)   // stopFlag not set, must raise error
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
```

If you run the server coded above, open a telnet session against it and send an
`STOP` message you will see how the server finishes properly.

But there is a catch yet. If there are several clients connected, sending an
`STOP` message will close the server's `Fiber` and the one attending the client
that sent the message. But the other `Fiber`s will keep running normally! It is
like they were daemon threads. Arguably, we could expect that shutting down the
server shall close _all_ connections. How could we do it? Solving that issue is
the proposed final exercise below.

Final exercise
--------------
We need to close all connections with clients when the server is shut down. To
do that we can call `cancel` on each one of the `Fiber` instances we have
created to attend each new client. But how? After all, we are not tracking
which `Fiber`s are running at any given time.

We could keep a list of active `Fiber`s serving client connections. It is
doable, but cumbersome...  and not really needed at this point.

Think about it: we have a `stopFlag` that signals when the server must be
stopped. When that flag is set we can assume we shall close all client
connections too. Thus  what we need to do is, every time we create a new
`Fiber` to attend some new client, we must also make sure that when `stopFlag`
is set that `Fiber` is canceled. As `Fiber` instances are very light we can
create a new instance just to wait for `stopFlag.read` and then cancelling the
client's own `Fiber`. This is how the `serve` method will look like now with
that change:

```scala
def serve(serverSocket: ServerSocket, stopFlag: MVar[IO, Unit]): IO[Unit] = {

  def close(socket: Socket): IO[Unit] = 
    IO{ socket.close() }.handleErrorWith(_ => IO.unit)

  for {
    _       <- IO.cancelBoundary
    socketE <- IO{ serverSocket.accept() }.attempt
    _       <- socketE match {
      case Right(socket) =>
        for { // accept() succeeded, we attend the client in its own Fiber
          fiber <- echoProtocol(socket, stopFlag)
                     .guarantee(close(socket))     // We close the server whatever happens
                     .start                        // Client attended by its own Fiber
          _     <- (stopFlag.read *> fiber.cancel)
                     .start                        // Another Fiber to cancel the client when stopFlag is set
          _     <- serve(serverSocket, stopFlag)   // Looping back to the beginning
        } yield ()
      case Left(e) =>
        for { // accept() failed, stopFlag will tell us whether this is a graceful shutdown
          isEmpty <- stopFlag.isEmpty
          _       <- if(!isEmpty) IO.unit  // stopFlag is set, nothing to do
                     else IO.raiseError(e) // stopFlag not set, must raise error
        } yield ()
    }
  } yield ()
  }
```

But note that when client socket is closed an exception will be raised by the
`reader.readLine()` call in the `loop` method of `echoProtocol`. As it happened
before with the server socket, the exception will be shown as an ugly error
message in the console. To prevent this we modify the `loop` function so it uses
`attempt` to control possible errors. If some error is detected first the state
of `stopFlag` is checked, and if it is set a graceful shutdown is assumed and no
action is taken; otherwise the error is raised:

```scala
def loop(reader: BufferedReader, writer: BufferedWriter): IO[Unit] = 
  for {
    _     <- IO.cancelBoundary
    lineE <- IO{ reader.readLine() }.attempt
    _     <- lineE match {
               case Right(line) => line match {
                 case "STOP" => stopFlag.put(()) // Returns IO[Unit], which is handy as we are done here
                 case ""     => IO.unit          // Empty line, we are done
                 case _      => IO{ writer.write(line); writer.newLine(); writer.flush() } *> loop(reader, writer)
               }
               case Left(e) =>
                 for { // readLine() failed, stopFlag will tell us whether this is a graceful shutdown
                   isEmpty <- stopFlag.isEmpty
                   _       <- if(!isEmpty) IO.unit  // stopFlag is set, nothing to do
                              else IO.raiseError(e) // stopFlag not set, must raise error
                 } yield ()
             }
  } yield ()
```

There are many other improvements that can be applied to this code, like for
example modifying `serve` so instead of using a hardcoded call to `echoProtocol`
it calls to some method passed as parameter. That way the same function could be
used for any protocol! Signature would be something like:

```scala
def serve(serverSocket: ServerSocket, protocol: (Socket, MVar[IO, Unit]) => IO[Unit], stopFlag: MVar[IO, Unit]): IO[Unit] = ???
```

After modifying `serve` code we would only need to change `server` so it includes
the protocol to use in its call to `serve`.

And what about trying to develop different protocols!?
