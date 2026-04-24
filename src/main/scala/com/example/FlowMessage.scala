package com.example

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.receptionist.ServiceKey
import StreamToActorMessaging._

/**
 * Marker trait for Jackson CBOR serialization.
 */
trait CborSerializable

object FlowMessage {

  @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
  trait FlowMessage extends CborSerializable

  // ── Raw Kafka message — no parsing done here ──────────────────────────────
  // The downstream Router/service is responsible for interpreting the bytes.
  case class RawMessage(
    topic: String,          // Kafka topic name the message came from
    key:   String,          // Kafka record key (may be null/empty)
    value: Array[Byte]      // Raw protobuf bytes — unparsed
  ) extends FlowMessage

  // ServiceKey for router registration
  val RouterKey: ServiceKey[StreamToActorMessage[FlowMessage]] =
    ServiceKey[StreamToActorMessage[FlowMessage]]("RouterService")
}
