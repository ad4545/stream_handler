package com.example

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.actorRefAdapter
import org.apache.pekko.stream.stage.GraphStage
import org.apache.pekko.stream.{ Attributes, FlowShape, Inlet, Outlet }
import org.apache.pekko.stream.stage.{ GraphStageLogic, InHandler, OutHandler }
import org.apache.pekko.actor.Status.Failure
import org.apache.pekko.actor.Terminated

/**
 * Fire-and-forget GraphStage that bridges a Pekko Stream to an ActorRef.
 * This stage forwards every element to the target actor and then passes it downstream.
 */
class GraphFlowStageActor[T](private val actorRef: ActorRef)
    extends GraphStage[FlowShape[T, T]] {
  import StreamToActorMessaging._

  val in: Inlet[T]   = Inlet("ActorFlowIn")
  val out: Outlet[T] = Outlet("ActorFlowOut")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      def stageActorReceive(messageWithActor: (ActorRef, Any)): Unit =
        messageWithActor match {
          case (_, StreamElementOut(element)) =>
            emit(out, element.asInstanceOf[T])

          case (_, StreamElementOutWithAck(element)) =>
            emit(out, element.asInstanceOf[T])

          case (_, StreamAck) => // fire-and-forget: ignore acks

          case (_, Failure(ex)) =>
            println(s"[GraphFlowStageActor] Remote actor failed: ${ex.getMessage}")

          case (_, Terminated(targetRef)) =>
            println(s"[GraphFlowStageActor] Remote actor terminated: $targetRef")

          case (ref, unexpected) =>
            println(s"[GraphFlowStageActor] Unexpected message: $unexpected from: $ref")
        }

      private lazy val self = getStageActor(stageActorReceive)

      override def preStart(): Unit =
        tellTargetActor(StreamInit(self.ref))

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val element = grab(in)
            tellTargetActor(StreamElementIn[T](element, self.ref))
            push(out, element)
          }
          override def onUpstreamFailure(ex: Throwable): Unit = super.onUpstreamFailure(ex)
          override def onUpstreamFinish(): Unit                = super.onUpstreamFinish()
        }
      )

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit =
            if (!hasBeenPulled(in)) pull(in)
          override def onDownstreamFinish(cause: Throwable): Unit =
            super.onDownstreamFinish(cause)
        }
      )

      private def tellTargetActor(message: Any): Unit =
        try {
          actorRef ! message
        } catch {
          case ex: Exception =>
            println(s"[GraphFlowStageActor] Exception while telling target actor: ${ex.getMessage}")
            ex.printStackTrace()
        }
    }

  override def shape: FlowShape[T, T] = FlowShape(in, out)
}
