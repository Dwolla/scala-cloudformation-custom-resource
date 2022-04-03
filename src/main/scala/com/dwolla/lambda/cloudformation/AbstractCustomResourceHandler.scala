package com.dwolla.lambda.cloudformation

import java.io._

import cats.data._
import cats.effect._
import cats.implicits._
import com.amazonaws.services.lambda.runtime._
import com.dwolla.lambda.cloudformation.AbstractCustomResourceHandler.stackTraceLines
import io.circe._
import io.circe.parser._
import org.apache.logging.log4j._

import scala.concurrent.ExecutionContext
import scala.io.Source
import cats.effect.Temporal

abstract class IOCustomResourceHandler(protected val executionContext: ExecutionContext) extends RequestStreamHandler { ioLambda =>
  def this() = this(ExecutionContext.global)

  protected implicit def contextShift: ContextShift[IO] = cats.effect.IO.contextShift(executionContext)
  protected implicit def timer: Temporal[IO] = cats.effect.IO.timer(executionContext)

  def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse]

  private val handlerProxy = new AbstractCustomResourceHandler[IO] {
    override def handleRequest(req: CloudFormationCustomResourceRequest): IO[HandlerResponse] =
      ioLambda.handleRequest(req)
  }

  override def handleRequest(inputStream: InputStream, outputStream: OutputStream, context: Context): Unit =
    handlerProxy.handleRequestAndWriteResponse(inputStream).unsafeRunSync()
}

abstract class AbstractCustomResourceHandler[F[_] : Sync] {
  def handleRequest(req: CloudFormationCustomResourceRequest): F[HandlerResponse]

  private def readInputStream(inputStream: InputStream): EitherT[F, Throwable, String] =
    Sync[F].delay(Source.fromInputStream(inputStream).mkString).attemptT

  private def parseStringLoggingErrors(str: String): EitherT[F, Throwable, Json] =
    parse(str).toEitherT[F].leftSemiflatMap(ex => Sync[F].delay {
      logger.error(s"Could not parse the following input:\n$str", ex)
      ex
    })

  private def parseInputStream(inputStream: InputStream): EitherT[F, Throwable, CloudFormationCustomResourceRequest] =
    for {
      str <- readInputStream(inputStream)
      json <- parseStringLoggingErrors(str)
      req <- json.as[CloudFormationCustomResourceRequest].toEitherT[F].leftWiden[Throwable]
    } yield req

  def handleRequestAndWriteResponse(inputStream: InputStream): F[Unit] =
    parseInputStream(inputStream)
      .semiflatMap { req =>
        for {
          res <- handleRequest(req).attemptT.fold(exceptionResponse(req), successResponse(req))
          _ <- responseWriter.logAndWriteToS3(req.ResponseURL, res)
        } yield ()
      }
      .valueOrF { ex =>
        Sync[F].delay(logger.error("failure", ex))
          .flatMap(_ => ex.raiseError[F, Unit])
      }

  protected def responseWriter: CloudFormationCustomResourceResponseWriter[F] = new CloudFormationCustomResourceResponseWriter[F]()

  protected lazy val logger: Logger = LogManager.getLogger("LambdaLogger")

  private def exceptionResponse(req: CloudFormationCustomResourceRequest)(ex: Throwable) =
    CloudFormationCustomResourceResponse(
      Status = RequestResponseStatus.Failed,
      Reason = Option(ex.getMessage),
      PhysicalResourceId = req.PhysicalResourceId,
      StackId = req.StackId,
      RequestId = req.RequestId,
      LogicalResourceId = req.LogicalResourceId,
      Data = JsonObject("StackTrace" -> Json.arr(stackTraceLines(ex).map(Json.fromString): _*))
    )

  private def successResponse(req: CloudFormationCustomResourceRequest)(res: HandlerResponse) =
    CloudFormationCustomResourceResponse(
      Status = RequestResponseStatus.Success,
      Reason = None,
      PhysicalResourceId = Option(res.physicalId),
      StackId = req.StackId,
      RequestId = req.RequestId,
      LogicalResourceId = req.LogicalResourceId,
      Data = res.data
    )

}

object AbstractCustomResourceHandler {
  def stackTraceLines(throwable: Throwable): List[String] = {
    val writer = new StringWriter()
    throwable.printStackTrace(new PrintWriter(writer))
    writer.toString.linesIterator.toList
  }
}
