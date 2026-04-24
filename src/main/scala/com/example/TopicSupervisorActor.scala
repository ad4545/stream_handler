package com.example

import org.apache.pekko.actor.typed.{ ActorRef, Behavior, SupervisorStrategy }
import org.apache.pekko.actor.typed.scaladsl.{ ActorContext, Behaviors }
import org.apache.pekko.actor.typed.receptionist.Receptionist
import FlowMessage._
import StreamToActorMessaging._

object TopicSupervisorActor {

  sealed trait Command extends CborSerializable
  case class NewTopicDiscovered(topicName: String) extends Command
  private case class RouterListing(listing: Receptionist.Listing) extends Command

  private case class State(
    currentRouter: Option[ActorRef[StreamToActorMessage[FlowMessage]]],
    activeConsumers: Map[String, ActorRef[ConsumerActor.ConsumerCommand]]
  )

  def apply(bootstrapServers: String): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info("[TopicSupervisor] Starting up and subscribing to Receptionist")

      val listingAdapter = context.messageAdapter[Receptionist.Listing](RouterListing(_))
      context.system.receptionist ! Receptionist.Subscribe(RouterKey, listingAdapter)

      active(
        State(currentRouter = None, activeConsumers = Map.empty),
        bootstrapServers
      )
    }
  }

  private def active(state: State, bootstrapServers: String): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case RouterListing(RouterKey.Listing(refs)) if refs.nonEmpty =>
          val routerRef = refs.head
          context.log.info("[TopicSupervisor] Router joined cluster")
          state.activeConsumers.foreach { case (_, ref) => ref ! ConsumerActor.RouterUpdated(routerRef) }
          active(state.copy(currentRouter = Some(routerRef)), bootstrapServers)

        case RouterListing(RouterKey.Listing(_)) =>
          context.log.warn("[TopicSupervisor] Router lost")
          state.activeConsumers.foreach { case (_, ref) => ref ! ConsumerActor.RouterLost }
          active(state.copy(currentRouter = None), bootstrapServers)

        case NewTopicDiscovered(topicName) =>
          if (state.activeConsumers.contains(topicName)) {
            Behaviors.same
          } else {
            context.log.info("[TopicSupervisor] Spawning consumer for topic: {}", topicName)
            val entry = spawnConsumer(context, topicName, state.currentRouter, bootstrapServers)
            active(state.copy(activeConsumers = state.activeConsumers + entry), bootstrapServers)
          }

        case RouterListing(_) => Behaviors.same
      }
    }

  private def spawnConsumer(
    context: ActorContext[Command],
    topic: String,
    routerRef: Option[ActorRef[StreamToActorMessage[FlowMessage]]],
    bootstrapServers: String
  ): (String, ActorRef[ConsumerActor.ConsumerCommand]) = {
    val safeName = topic.replaceAll("[^a-zA-Z0-9_-]", "_")
    val consumerRef = context.spawn(
      Behaviors.supervise(
        ConsumerActor(topic, routerRef, bootstrapServers)
      ).onFailure[Exception](SupervisorStrategy.resume),
      s"consumer-$safeName"
    )
    topic -> consumerRef
  }
}
