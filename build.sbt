name := "stream_handler"
version := "0.1"
scalaVersion := "3.4.2"

// ── Version pins ─────────────────────────────────────────────────────────────
val pekkoVersion     = "1.1.2"
val pekkoHttpVersion = "1.1.0"
val scalapbVersion   = "0.11.15"


// ── Dependencies ──────────────────────────────────────────────────────────────
libraryDependencies ++= Seq(
  // Apache Pekko core (open-source Akka fork — Apache 2.0)
  "org.apache.pekko" %% "pekko-actor-typed"            % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream"                 % pekkoVersion,
  "org.apache.pekko" %% "pekko-stream-typed"           % pekkoVersion,
  "org.apache.pekko" %% "pekko-http"                   % pekkoHttpVersion,
  "org.apache.pekko" %% "pekko-discovery"              % pekkoVersion,
  "org.apache.pekko" %% "pekko-remote"                 % pekkoVersion,
  "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
  "org.apache.pekko" %% "pekko-slf4j"                  % pekkoVersion,

  // Jackson serialization — required at runtime by pekko-serialization-jackson
  // which is pulled in transitively by pekko-cluster; listing it explicitly
  // ensures it is on the classpath and avoids ClassNotFoundException at startup.
  "org.apache.pekko" %% "pekko-serialization-jackson"  % pekkoVersion,

  // Pekko Connectors Kafka (replaces akka-stream-kafka / Alpakka Kafka)
  // 1.0.0 is the stable release that works with pekko 1.1.x
  "org.apache.pekko" %% "pekko-connectors-kafka"       % "1.0.0",


  // Logging
  "com.typesafe.scala-logging" %% "scala-logging"      % "3.9.5",
  "ch.qos.logback"              % "logback-classic"    % "1.5.6",

  // Kafka AdminClient (for TopicDiscoveryActor)
  "org.apache.kafka"            % "kafka-clients"      % "3.7.0",

  // Fansi (Scala 2.13 cross-build shim for Scala 3)
  ("com.lihaoyi" %% "fansi" % "0.4.0").cross(CrossVersion.for3Use2_13)
)
