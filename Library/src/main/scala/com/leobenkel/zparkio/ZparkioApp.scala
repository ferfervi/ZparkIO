package com.leobenkel.zparkio

import com.leobenkel.zparkio.Services.CommandLineArguments.HelpHandlerException
import com.leobenkel.zparkio.Services._
import org.rogach.scallop.exceptions.ScallopException
import zio.console.Console
import zio.duration.Duration
import zio.{DefaultRuntime, IO, Task, UIO, ZIO}

trait ZparkioApp[C <: CommandLineArguments.Service, ENV <: ZparkioApp.ZPEnv[C] with Logger, OUTPUT]
    extends DefaultRuntime {

  protected def makeSparkBuilder: SparkModule.Builder[C]
  protected def makeCliBuilder:   CommandLineArguments.Builder[C]
  protected def displayCommandLines: Boolean = true
  protected def makeLogger: Logger

  protected def makeEnvironment(
    cliService:    C,
    loggerService: Logger.Service,
    sparkService:  SparkModule.Service
  ): ENV

  protected def runApp(): ZIO[ENV, Throwable, OUTPUT]

  protected def processErrors(f: Throwable): Option[Int] = Some(1)
  protected def timedApplication: Duration = Duration.Infinity

  private object ErrorProcessing {
    def unapply(e: Throwable): Option[Int] = {
      processErrors(e)
    }
  }

  protected def buildEnv(args: List[String]): ZIO[zio.ZEnv, Throwable, ENV] = {
    for {
      c          <- ZIO.environment[Console]
      logger     <- Task(makeLogger)
      cliBuilder <- Task(makeCliBuilder)
      cliService <- cliBuilder.createCliSafely(args).tapError {
        case cliError: ScallopException =>
          Logger
            .displayAllErrors(cliError).provide(new Logger with Console {
              lazy final override val log:     Logger.Service = logger.log
              lazy final override val console: Console.Service[Any] = c.console
            })
        case _ => UIO(())
      }
      sparkBuilder <- Task(makeSparkBuilder)
      sparkService <- sparkBuilder.createSpark(cliService)
    } yield { makeEnvironment(cliService, logger.log, sparkService) }
  }

  protected def app(args: List[String]): ZIO[zio.ZEnv, Throwable, OUTPUT] = {
    for {
      env <- buildEnv(args)
      _ <- if (displayCommandLines) {
        CommandLineArguments.displayCommandLines().provide(env)
      } else {
        UIO(())
      }
      output <- runApp()
        .provide(env)
        .timeoutFail(ZparkioApplicationTimeoutException())(timedApplication)
    } yield { output }
  }

  protected def run(args: List[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    app(args)
      .catchSome { case h: HelpHandlerException => h.printHelpMessage }
      .fold(
        {
          case CommandLineArguments.ErrorParser(code) => code
          case ErrorProcessing(errorCode)             => errorCode
          case _                                      => 1
        },
        _ => 0
      )
  }

  protected def wrappedRun(args0: Array[String]): ZIO[zio.ZEnv, Nothing, Int] = {
    for {
      fiber <- run(args0.toList).fork
      _ <- IO.effectTotal(java.lang.Runtime.getRuntime.addShutdownHook(new Thread {
        override def run(): Unit = {
          val _ = unsafeRunSync(fiber.interrupt)
        }
      }))
      result <- fiber.join
    } yield result
  }

  // $COVERAGE-OFF$ Bootstrap to `Unit`
  final def main(args0: Array[String]): Unit = {
    val exitCode = unsafeRun(wrappedRun(args0))
    println(s"ExitCode: $exitCode")
  }
  // $COVERAGE-ON$
}

object ZparkioApp {
  type ZPEnv[C <: CommandLineArguments.Service] =
    zio.ZEnv with CommandLineArguments[C] with Logger with SparkModule
}
