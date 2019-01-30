package com.dwolla.lambda

import cats.implicits._
import shapeless.tag.@@
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._

package object cloudformation {

  type PhysicalResourceId = String @@ PhysicalResourceIdTag
  type StackId = String @@ StackIdTag
  type RequestId = String @@ RequestIdTag
  type LogicalResourceId = String @@ LogicalResourceIdTag
  type ResourceType = String @@ ResourceTypeTag

  val tagPhysicalResourceId: String => PhysicalResourceId = shapeless.tag[PhysicalResourceIdTag][String]
  private[cloudformation] val tagStackId: String => StackId = shapeless.tag[StackIdTag][String]
  private[cloudformation] val tagRequestId: String => RequestId = shapeless.tag[RequestIdTag][String]
  private[cloudformation] val tagLogicalResourceId: String => LogicalResourceId = shapeless.tag[LogicalResourceIdTag][String]
  private[cloudformation] val tagResourceType: String => ResourceType = shapeless.tag[ResourceTypeTag][String]

  private[cloudformation] implicit def encodeTaggedString[A]: Encoder[String @@ A] = Encoder[String].narrow
  private[cloudformation] implicit def decodeTaggedString[A]: Decoder[String @@ A] = Decoder[String].map(shapeless.tag[A][String])

}

package cloudformation {
  trait PhysicalResourceIdTag
  trait StackIdTag
  trait RequestIdTag
  trait LogicalResourceIdTag
  trait ResourceTypeTag

  sealed trait CloudFormationRequestType
  object CloudFormationRequestType {
    case object CreateRequest extends CloudFormationRequestType
    case object UpdateRequest extends CloudFormationRequestType
    case object DeleteRequest extends CloudFormationRequestType
    case class OtherRequestType(requestType: String) extends CloudFormationRequestType

    implicit val encoder: Encoder[CloudFormationRequestType] = {
      case CreateRequest => "CREATE".asJson
      case UpdateRequest => "UPDATE".asJson
      case DeleteRequest => "DELETE".asJson
      case OtherRequestType(req) => req.asJson
    }

    implicit val decoder: Decoder[CloudFormationRequestType] = Decoder[String].map {
      case "CREATE" => CreateRequest
      case "UPDATE" => UpdateRequest
      case "DELETE" => DeleteRequest
      case other => OtherRequestType(other)
    }
  }

  sealed trait RequestResponseStatus
  object RequestResponseStatus {
    case object Success extends RequestResponseStatus
    case object Failed extends RequestResponseStatus

    implicit val encoder: Encoder[RequestResponseStatus] = {
      case Success => "SUCCESS".asJson
      case Failed => "FAILED".asJson
    }

    implicit val decoder: Decoder[RequestResponseStatus] = Decoder[String].map {
      case "SUCCESS" => Success
      case "FAILED" => Failed
    }
  }

  case class CloudFormationCustomResourceRequest(RequestType: CloudFormationRequestType,
                                                 ResponseURL: String,
                                                 StackId: StackId,
                                                 RequestId: RequestId,
                                                 ResourceType: ResourceType,
                                                 LogicalResourceId: LogicalResourceId,
                                                 PhysicalResourceId: Option[PhysicalResourceId],
                                                 ResourceProperties: Option[JsonObject],
                                                 OldResourceProperties: Option[JsonObject])

  object CloudFormationCustomResourceRequest {
    implicit val encoder = deriveEncoder[CloudFormationCustomResourceRequest]
    implicit val decoder = deriveDecoder[CloudFormationCustomResourceRequest]
  }

  case class CloudFormationCustomResourceResponse(Status: RequestResponseStatus,
                                                  Reason: Option[String],
                                                  PhysicalResourceId: Option[PhysicalResourceId],
                                                  StackId: StackId,
                                                  RequestId: RequestId,
                                                  LogicalResourceId: LogicalResourceId,
                                                  Data: JsonObject = JsonObject.empty)

  object CloudFormationCustomResourceResponse {
    implicit val encoder = deriveEncoder[CloudFormationCustomResourceResponse]
    implicit val decoder = deriveDecoder[CloudFormationCustomResourceResponse]
  }

  case class HandlerResponse(physicalId: PhysicalResourceId,
                             data: JsonObject = JsonObject.empty)

  object HandlerResponse {
    implicit val encoder = deriveEncoder[HandlerResponse]
    implicit val decoder = deriveDecoder[HandlerResponse]
  }

  object MissingResourceProperties extends RuntimeException
}
