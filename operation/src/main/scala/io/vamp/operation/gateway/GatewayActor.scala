package io.vamp.operation.gateway

import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.vamp.common.akka._
import io.vamp.operation.notification._
import io.vamp.persistence.{ ArtifactPaginationSupport, ArtifactSupport }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{ existentials, postfixOps }

object GatewayActor {

  val configuration = ConfigFactory.load().getConfig("vamp.operation.gateway")

  val timeout = Timeout(configuration.getInt("response-timeout") seconds)

  val (portRangeLower: Int, portRangeUpper: Int) = {
    val portRange = configuration.getString("port-range").split("-").map(_.toInt)
    (portRange(0), portRange(1))
  }

  trait GatewayMessages

  object GetAvailablePortNumber extends GatewayMessages

}

class GatewayActor extends CommonSupportForActors with ArtifactSupport with ArtifactPaginationSupport with OperationNotificationProvider {

  import GatewayActor._

  /*
  private var currentPort = portRangeLower - 1


  case portAssignment(deployment, port) ⇒
      if (currentPort == portRange(1))
        reportException(NoAvailablePortError(portRange(0), portRange(1)))
      else {
        currentPort += 1
        currentPort
      }
   */
  def receive = {
    case GetAvailablePortNumber ⇒ reply {
      Future.successful(0)
    }

    case any ⇒ unsupported(UnsupportedDeploymentRequest(any))
  }

}
