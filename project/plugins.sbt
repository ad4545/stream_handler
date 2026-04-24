// sbt-protoc + ScalaPB: open-source protobuf code generation (Apache 2.0)
// Replaces the commercial sbt-akka-grpc plugin entirely.
// On Apple Silicon (arm64) sbt-protoc downloads the correct protoc binary automatically.
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.15"

// Metals / Bloop IDE support (optional but useful)
addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "2.0.0")
