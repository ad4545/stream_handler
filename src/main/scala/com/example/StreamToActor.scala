package com.example

import org.apache.pekko.actor.typed.ActorRef

object StreamToActorMessaging {

  @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
  sealed trait StreamToActorMessage[+T] extends CborSerializable

  case class StreamInit[T](replyTo: ActorRef[StreamToActorMessage[T]])
    extends StreamToActorMessage[T]

  case object StreamAck extends StreamToActorMessage[Nothing]

  case object StreamCompleted extends StreamToActorMessage[Nothing]

  case class StreamFailed(ex: Throwable) extends StreamToActorMessage[Nothing]

  case class StreamGetSource[T](replyTo: ActorRef[StreamToActorMessage[T]])
    extends StreamToActorMessage[T]

  case class StreamElementIn[T](
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
    in: T,
    replyTo: ActorRef[StreamToActorMessage[T]]
  ) extends StreamToActorMessage[T]

  case class StreamElementOut[T](
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
    msg: T
  ) extends StreamToActorMessage[T]

  case class StreamElementOutWithAck[T](
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.CLASS, property = "type")
    msg: T
  ) extends StreamToActorMessage[T]
}
