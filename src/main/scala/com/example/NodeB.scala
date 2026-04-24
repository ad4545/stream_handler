package com.example

import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

object KafkaConsumerNode {

  sealed trait NodeCommand extends CborSerializable
  private case object Started extends NodeCommand

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory
      .parseString("""
        pekko.remote.artery.canonical.port = 25520
      """)
      .withFallback(ConfigFactory.load())

    val bootstrapServers  = config.getString("kafka.bootstrap-servers")
    val topicPrefix       = config.getString("kafka.topic-prefix")
    val discoveryInterval = FiniteDuration(config.getDuration("kafka.discovery-interval").toMillis, MILLISECONDS)

    System.err.println(s"[trace-node] START: Launching NodeB (Main Method)")

    val system = ActorSystem[NodeCommand](
      Behaviors.setup[NodeCommand] { ctx =>
        System.err.println("[trace-node] Inside Guardian Setup")

        System.err.println("[trace-node] Spawning TopicSupervisor...")
        val supervisor = ctx.spawn(TopicSupervisorActor(bootstrapServers), "topic-supervisor")
        System.err.println(s"[trace-node] SPAWNED TopicSupervisor: $supervisor")

        System.err.println("[trace-node] Spawning TopicDiscovery...")
        val discovery = ctx.spawn(
          TopicDiscoveryActor(
            bootstrapServers  = bootstrapServers,
            topicPrefix       = topicPrefix,
            discoveryInterval = discoveryInterval,
            supervisor        = supervisor
          ),
          "topic-discovery"
        )
        System.err.println(s"[trace-node] SPAWNED TopicDiscovery: $discovery")

        ctx.log.info("[NodeB] All actors spawned successfully")
        
        Behaviors.receiveMessage {
          case _ => Behaviors.same
        }
      },
      "ClusterSystem",
      config
    )

    System.err.println("[trace-node] ActorSystem started. Press ENTER in this terminal to terminate.")
    
    // We are now safely outside the static initializer, so readLine won't block class loading.
    scala.io.StdIn.readLine()
    
    System.err.println("[trace-node] Shutting down...")
    system.terminate()
  }
}
