package com.dwolla.lambda.cloudformation

import java.io._

import cats.effect._
import com.amazonaws.services.lambda.runtime.Context
import com.dwolla.lambda.cloudformation.SampleMessages._
import io.circe._
import io.circe.syntax._
import io.circe.literal._
import org.mockito._
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import org.apache.logging.log4j._

import scala.concurrent._

class CloudFormationCustomResourceHandlerSpec(implicit ee: ExecutionEnv) extends Specification with Mockito {

  implicit def listStringToJson(l: List[String]): Json = Json.arr(l.map(Json.fromString): _*)

  val spaces2OmitNulls = Printer.indented("  ").copy(dropNullValues = true, colonLeft = "", colonRight = "")

  "IO-based Handler" should {
    trait IOSetup extends AbstractCustomResourceHandler[IO] with Scope {
      val context = mock[Context]
      val mockLogger = mock[Logger]
      val outputStream = mock[OutputStream]
      val mockResponseWriter = mock[CloudFormationCustomResourceResponseWriter[IO]]

      override protected lazy val logger: Logger = mockLogger

      override protected def responseWriter = mockResponseWriter
    }

    "deserialize input, pass req to handler, and convert output to response format" in new IOSetup {
      val promisedRequest = Promise[CloudFormationCustomResourceRequest]()

      override def handleRequest(input: CloudFormationCustomResourceRequest) = IO {
        promisedRequest.success(input)
        HandlerResponse(physicalId = tagPhysicalResourceId("physical-id"))
      }

      private val expectedResponse = CloudFormationCustomResourceResponse(
        Status = RequestResponseStatus.Success,
        StackId = tagStackId("arn:aws:cloudformation:us-west-2:EXAMPLE/stack-name/guid"),
        LogicalResourceId = tagLogicalResourceId("MyTestResource"),
        RequestId = tagRequestId("unique id for this create request"),
        PhysicalResourceId = Option("physical-id").map(tagPhysicalResourceId),
        Reason = None
      )

      mockResponseWriter.logAndWriteToS3("http://pre-signed-S3-url-for-response", expectedResponse) returns IO.unit

      this.handleRequestAndWriteResponse(new StringInputStream(CloudFormationCustomResourceInputJson)).unsafeRunSync()

      promisedRequest.future must be_==(CloudFormationCustomResourceRequest(
        RequestType = CloudFormationRequestType.CreateRequest,
        ResponseURL = "http://pre-signed-S3-url-for-response",
        StackId = tagStackId("arn:aws:cloudformation:us-west-2:EXAMPLE/stack-name/guid"),
        RequestId = tagRequestId("unique id for this create request"),
        ResourceType = tagResourceType("Custom::TestResource"),
        LogicalResourceId = tagLogicalResourceId("MyTestResource"),
        PhysicalResourceId = None,
        ResourceProperties = Option(JsonObject("StackName" -> Json.fromString("stack-name"), "List" -> Json.arr(List("1", "2", "3").map(Json.fromString): _*))),
        OldResourceProperties = None
      )).await
      there was one(mockResponseWriter).logAndWriteToS3("http://pre-signed-S3-url-for-response", expectedResponse)
    }

    "log json if a parse error occurs" in new IOSetup {
      this.handleRequestAndWriteResponse(new StringInputStream(invalidJson)).attempt.unsafeRunSync() must beLeft[Throwable].like {
        case ex: ParsingFailure => ex must not beNull
      }

      there was one(mockLogger).error(ArgumentMatchers.eq(
        s"""Could not parse the following input:
           |$invalidJson""".stripMargin), any[ParsingFailure])

      //noinspection NotImplementedCode
      override def handleRequest(req: CloudFormationCustomResourceRequest) = ???
    }

    "return a failure if the handler throws an exception" in new IOSetup {
      override def handleRequest(req: CloudFormationCustomResourceRequest) = IO.raiseError(NoStackTraceException)

      private val expectedResponse = CloudFormationCustomResourceResponse(
        Status = RequestResponseStatus.Failed,
        StackId = tagStackId("arn:aws:cloudformation:us-west-2:EXAMPLE/stack-name/guid"),
        LogicalResourceId = tagLogicalResourceId("MyTestResource"),
        RequestId = tagRequestId("unique id for this create request"),
        PhysicalResourceId = None,
        Reason = Option("exception deliberately thrown by test"),
        Data = JsonObject("StackTrace" -> List("com.dwolla.lambda.cloudformation.NoStackTraceException$: exception deliberately thrown by test"))
      )

      mockResponseWriter.logAndWriteToS3("http://pre-signed-S3-url-for-response", expectedResponse) returns IO.unit

      this.handleRequestAndWriteResponse(new StringInputStream(CloudFormationCustomResourceInputJson)).unsafeRunSync()

      there was one(mockResponseWriter).logAndWriteToS3("http://pre-signed-S3-url-for-response", expectedResponse)
    }
  }

  "Response" should {
    "be serializable" >> {
      val exception = new WritableStackTraceRuntimeException
      exception.setStackTrace(Array(new StackTraceElement("class", "method", "filename", 42)))
      exception.addSuppressed(new WritableStackTraceRuntimeException("suppressed exception intentionally thrown by test"))

      val stackTrace = {
        val out = new StringWriter()
        exception.printStackTrace(new PrintWriter(out))
        out.toString.linesIterator.toList
      }

      val expectedResponse = CloudFormationCustomResourceResponse(
        Status = RequestResponseStatus.Failed,
        StackId = tagStackId("arn:aws:cloudformation:us-west-2:EXAMPLE/stack-name/guid"),
        LogicalResourceId = tagLogicalResourceId("MyTestResource"),
        RequestId = tagRequestId("unique id for this create request"),
        PhysicalResourceId = None,
        Reason = Option("exception intentionally thrown by test"),
        Data = JsonObject(
          "StackTrace" -> stackTrace
        )
      )

      expectedResponse.asJson must_==
        json"""{
                 "Status":"FAILED",
                 "Reason":"exception intentionally thrown by test",
                 "StackId":"arn:aws:cloudformation:us-west-2:EXAMPLE/stack-name/guid",
                 "RequestId":"unique id for this create request",
                 "LogicalResourceId":"MyTestResource",
                 "PhysicalResourceId": null,
                 "Data":{
                   "StackTrace":[
                     "com.dwolla.lambda.cloudformation.WritableStackTraceRuntimeException: exception intentionally thrown by test",
                     "\tat class.method(filename:42)",
                     "\tSuppressed: com.dwolla.lambda.cloudformation.WritableStackTraceRuntimeException: suppressed exception intentionally thrown by test"
                   ]
                 }
               }"""
    }
  }
}

object SampleMessages {
  val CloudFormationCustomResourceInputJson: String =
    json"""{
             "StackId": "arn:aws:cloudformation:us-west-2:EXAMPLE/stack-name/guid",
             "ResponseURL": "http://pre-signed-S3-url-for-response",
             "ResourceProperties": {
               "StackName": "stack-name",
               "List": [
                 "1",
                 "2",
                 "3"
               ]
             },
             "RequestType": "Create",
             "ResourceType": "Custom::TestResource",
             "RequestId": "unique id for this create request",
             "LogicalResourceId": "MyTestResource"
           }""".spaces2

  val invalidJson = "}"
}

class WritableStackTraceRuntimeException(message: String = "exception intentionally thrown by test") extends RuntimeException(message, null, true, true) {
  override def fillInStackTrace(): Throwable = this
}

case object NoStackTraceException extends RuntimeException("exception deliberately thrown by test", null, true, false)

class StringInputStream(str: String) extends InputStream {
  private val bais = new ByteArrayInputStream(str.getBytes)

  override def read(): Int = bais.read()
}
