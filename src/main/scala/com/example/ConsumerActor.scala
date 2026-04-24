package com.example

import org.apache.pekko.actor.typed.{ ActorRef, Behavior, PostStop, PreRestart }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.kafka.common.serialization.{ ByteArrayDeserializer, StringDeserializer }
import org.apache.pekko.kafka.{ ConsumerSettings, Subscriptions }
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.{ ActorAttributes, KillSwitches, Supervision, UniqueKillSwitch }
import org.apache.pekko.stream.scaladsl.{ Flow, Keep, Sink }
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.NotUsed
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Random, Success }
import StreamToActorMessaging._
import FlowMessage._
import com.typesafe.scalalogging.LazyLogging

/**
 * ConsumerActor manages the Kafka-to-Pekko-Stream pipeline for a single topic.
 * It forwards raw Kafka bytes along with topic metadata to the Router node.
 */
object ConsumerActor extends LazyLogging {

  sealed trait ConsumerCommand extends CborSerializable
  case object RouterLost extends ConsumerCommand
  case class RouterUpdated(newRouter: ActorRef[StreamToActorMessage[FlowMessage]]) extends ConsumerCommand
  private case object StreamCompleted extends ConsumerCommand
  private case class StreamFailed(exception: Throwable) extends ConsumerCommand

  def apply(
    topic: String,
    initialRouter: Option[ActorRef[StreamToActorMessage[FlowMessage]]],
    bootstrapServers: String
  ): Behavior[ConsumerCommand] =
    Behaviors.setup[ConsumerCommand] { context =>
      implicit val system: org.apache.pekko.actor.typed.ActorSystem[?] = context.system
      implicit val ec: ExecutionContext = system.executionContext

      context.log.info("[ConsumerActor] Starting — topic='{}', router={}", topic, initialRouter)

      // Internal adapter actor to bridge the Stream GraphStage to the Cluster Router
      // It handles both stream messages (from GraphStage) and router updates (from parent).
      def adapter(
        currentRouter: Option[ActorRef[StreamToActorMessage[FlowMessage]]],
        wasWarned: Boolean
      ): Behavior[Any] =
        Behaviors.receive { (ctx, message) =>
          message match {
            case RouterUpdated(newRouter) =>
              logger.info(s"[ConsumerActor/Adapter] Received Router updated for '$topic': ${newRouter.path}")
              adapter(Some(newRouter), wasWarned = false)

            case RouterLost =>
              adapter(None, wasWarned = false)

            case streamMsg: StreamToActorMessage[FlowMessage @unchecked] =>
              currentRouter match {
                case Some(routerRef) =>
                  if (streamMsg.isInstanceOf[StreamElementIn[?]]) {
                    // Reduce noise in logs, just comment this out or log occasionally
                    // logger.debug(s"[ConsumerActor/Adapter] Forwarding data packet for topic '$topic' to Router at ${routerRef.path}")
                  }
                  routerRef ! streamMsg
                  adapter(currentRouter, false)
                case None =>
                  if (!wasWarned) {
                    ctx.log.warn("[ConsumerActor/Adapter] No Router available for topic '{}' — dropping messages", topic)
                  }
                  adapter(currentRouter, true)
              }

            case other =>
              ctx.log.warn(s"Unexpected message in adapter: $other")
              Behaviors.same
          }
        }

      val adapterActor: ActorRef[Any] = context.spawn(
        adapter(initialRouter, false), 
        s"adapter-${topic.replaceAll("[^a-zA-Z0-9_-]", "_")}"
      )
      val classicAdapterRef = adapterActor.toClassic

      val actorFlow: Flow[FlowMessage, FlowMessage, NotUsed] =
        Flow.fromGraph(new GraphFlowStageActor[FlowMessage](classicAdapterRef))

      val resumeOnError = ActorAttributes.supervisionStrategy { ex =>
        logger.warn(s"[ConsumerActor] Stream error on '$topic' — resuming: ${ex.getMessage}")
        Supervision.Resume
      }

      val kafkaConsumerSettings: ConsumerSettings[String, Array[Byte]] =
        ConsumerSettings(system, new StringDeserializer, new ByteArrayDeserializer)
          .withBootstrapServers(bootstrapServers)
          .withGroupId(s"stream-handler-$topic") // Keep group consistent to avoid re-reading the entire topic on every boot
          .withProperty("enable.auto.commit", "true")
          .withProperty("auto.commit.interval.ms", "1000")
          .withProperty("auto.offset.reset", "earliest") // For testing: ensure we grab available data immediately

      val (killSwitch: UniqueKillSwitch, streamDone) =
        Consumer
          .committableSource(kafkaConsumerSettings, Subscriptions.topics(topic))
          .withAttributes(resumeOnError)
          .viaMat(KillSwitches.single)(Keep.right)
          .map { msg =>
            val raw = RawMessage(
              topic = msg.record.topic(),
              key   = Option(msg.record.key()).getOrElse(""),
              value = msg.record.value()
            )
            raw: FlowMessage
          }
          .via(actorFlow)
          .toMat(Sink.ignore)(Keep.both)
          .run()

      context.pipeToSelf(streamDone) {
        case Success(_)  => StreamCompleted
        case Failure(ex) => StreamFailed(ex)
      }

      Behaviors
        .receive[ConsumerCommand] { (ctx, cmd) =>
          cmd match {
            case RouterLost =>
              adapterActor ! RouterLost
              Behaviors.same

            case ru @ RouterUpdated(newRouter) =>
              ctx.log.info("[ConsumerActor] Router discovery updated for topic '{}' → {}", topic, newRouter.path)
              adapterActor ! ru
              Behaviors.same

            case StreamCompleted =>
              Behaviors.stopped

            case StreamFailed(ex) =>
              ctx.log.error(s"[ConsumerActor] Stream failed for '$topic': ${ex.getMessage}")
              Behaviors.stopped
          }
        }
        .receiveSignal {
          case (ctx, PostStop) =>
            killSwitch.shutdown()
            Behaviors.same
          case (ctx, PreRestart) =>
            killSwitch.shutdown()
            Behaviors.same
        }
    }
}
