package com.example

import org.apache.pekko.actor.typed.{ ActorRef, Behavior }
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.kafka.clients.admin.{ AdminClient, AdminClientConfig }
import java.util.Properties
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

object TopicDiscoveryActor {

  sealed trait Command extends CborSerializable
  private case object Tick extends Command

  def apply(
    bootstrapServers: String,
    topicPrefix: String,
    discoveryInterval: FiniteDuration,
    supervisor: ActorRef[TopicSupervisorActor.Command]
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers[Command] { timers =>
        context.log.info("[TopicDiscovery] Starting discovery loop with prefix '{}'", topicPrefix)

        context.self ! Tick
        timers.startTimerWithFixedDelay("discovery-tick", Tick, discoveryInterval)

        val adminProps = new Properties()
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "15000")
        adminProps.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "20000")

        var knownTopics: Set[String] = Set.empty

        Behaviors.receiveMessage {
          case Tick =>
            context.log.debug("[TopicDiscovery] Polling Kafka for topics...")
            Try {
              val adminClient = AdminClient.create(adminProps)
              try {
                adminClient.listTopics().names().get().asScala.toSet
              } finally {
                adminClient.close()
              }
            } match {
              case Success(allTopics) =>
                val matchingTopics = allTopics.filter(_.startsWith(topicPrefix))
                val newTopics      = matchingTopics -- knownTopics

                context.log.info(
                  "[TopicDiscovery] Found {}/{} topics matching '{}'",
                  matchingTopics.size,
                  allTopics.size,
                  topicPrefix
                )

                if (newTopics.nonEmpty) {
                  context.log.info("[TopicDiscovery] Spawning consumers for NEW topics: {}", newTopics.mkString(","))
                  newTopics.foreach { t => supervisor ! TopicSupervisorActor.NewTopicDiscovered(t) }
                  knownTopics = knownTopics ++ newTopics
                }

              case Failure(ex) =>
                context.log.error(s"[TopicDiscovery] Connection to Kafka failed: ${ex.toString}")
            }
            Behaviors.same
        }
      }
    }
}
