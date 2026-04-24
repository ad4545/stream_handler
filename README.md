# Stream Handler — Technical & Architectural Documentation

## Overview
The **Stream Handler** acts as the high-throughput "Pump" in our distributed continuous streaming architecture. It is a dynamic ingestion node that automatically discovers new data topics in Kafka and bridges them into a highly resilient **Pekko Cluster**. It continuously extracts raw amr data and forwards it to the remote `Stream Router`, representing the first stage of the live data pipeline.

## Architectural Philosophy: Continuous Flow & Kafka Protection
The core idea behind the Stream Handler is to separate data ingestion from end-user consumption.
- **Protecting Kafka**: Instead of having every downstream microservice connect to Kafka directly (which would require heavy consumer groups, massive partition rebalancing, and overwhelm brokers with connection overhead), the Stream Handler acts as a **vanguard**. It is the *sole* consumer of these topics.
- **Continuous State in Motion**: The handler establishes a continuous, uninterrupted stream of data independent of whether any microservices are currently listening. It keeps the data flowing across the cluster, guaranteeing that as soon as a microservice decides to attach to the stream, the data is already there — hot, live, and flowing at the lowest possible latency.
- **Latest Data Priority**: The system is designed for live scenarios. While Kafka contains historical data, this architecture is optimized for microservices that need "Right Now" latency and don't care about historical replay. The handler aggressively pushes current state to keep the router's internal state fresh.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          STREAM HANDLER NODE                                    │
│                                                                                 │
│   ┌──────────────────────┐        ┌─────────────────────────────────────────┐  │
│   │  TopicDiscoveryActor │        │         TopicSupervisorActor            │  │
│   │                      │        │                                         │  │
│   │  polls Kafka Admin   │──────▶ │  - Spawns / kills ConsumerActors        │  │
│   │  every 30s for new   │        │  - Subscribes to Receptionist           │  │
│   │  "amr.*" topics      │        │  - Broadcasts RouterUpdated/RouterLost  │  │
│   └──────────────────────┘        └───────────────┬──────────────┬──────────┘  │
│                                                   │              │             │
│                                    ┌──────────────▼──┐   ┌───────▼──────────┐  │
│                                    │  ConsumerActor  │   │  ConsumerActor   │  │
│                                    │   amr.001       │   │   amr.002        │  │
│                                    │                 │   │                  │  │
│                                    │  [KillSwitch]   │   │  [KillSwitch]    │  │
│                                    │  [Adapter]      │   │  [Adapter]       │  │
│                                    └────────┬────────┘   └────────┬─────────┘  │
│                                             │                     │            │
└─────────────────────────────────────────────┼─────────────────────┼────────────┘
                                              │                     │
                          ┌───────────────────▼─────────────────────▼───────────┐
                          │              RouterActor  (Remote Node)              │
                          │         receives RawMessage over Pekko Cluster       │
                          └─────────────────────────────────────────────────────┘

  ┌─────────────────┐        ┌────────────────────┐
  │   Kafka Broker  │        │    Receptionist     │
  │                 │        │                     │
  │  amr.001   ─────┼──────▶ │  publishes RouterKey│──▶ TopicSupervisorActor
  │  amr.002   ─────┼──────▶ │  when Router joins  │
  └─────────────────┘        └────────────────────┘
```

---

## Component Architecture & Low-Level Mechanics

### 1. Topic Discovery (`TopicDiscoveryActor.scala`)
- **Action**: Operates a background polling loop against the Kafka `AdminClient`.
- **Logic**: It dynamically searches for topics matching predefined prefixes (e.g., `amr.`). When a new topic appears, it asynchronously notifies the Supervisor, making the system 100% reactive to dynamic infrastructure changes without hardcoded configurations.
- **Tuning**: Interval is defined by `kafka.discovery-interval` (default: 30s). Can be aggressively lowered securely because it's metadata-only.

### 2. Supervision (`TopicSupervisorActor.scala`)
- **Role**: The guardian of the consumer fleet. It ensures isolated fault-tolerance so a failure in `amr.001` consumption doesn't impact `amr.002`.
- **Location Transparency**: Listens to the Pekko `Receptionist` for the `RouterKey`. It decouples IP routing by dynamically pushing the network reference of the Router to every active `ConsumerActor`.
- **Elasticity**: Dynamically spawns or kills `ConsumerActor` instances per topic.

### 3. Topic Consumer (`ConsumerActor.scala`)
The most critical component for data reliability:
- **Kafka Integration**: Uses Alpakka Kafka with `auto.offset.reset = "latest"` (or earliest, depending on deployment needs) to maintain low latency and drop unneeded history.

---

#### Adapter Pattern

**The problem**: The Kafka stream (`GraphFlowStageActor`) and the cluster router are in different concurrency domains. The stream is driven by the Reactive Streams engine on its own thread; the router is a remote actor discovered dynamically at runtime. Sharing a mutable `var routerRef` between them is a race condition — the stream could read a stale or null reference mid-emission.

**The solution**: A dedicated child `adapter` actor owns the router reference as immutable state. The stream always targets the adapter (a stable local ref). The supervisor updates the adapter independently.

```
  ┌──────────────────────────────────────────────────────────────────────┐
  │                        ConsumerActor                                 │
  │                                                                      │
  │   ┌─────────────────────────────────────────────────────────────┐    │
  │   │                   Alpakka Stream (own thread)               │    │
  │   │                                                             │    │
  │   │   KafkaSource ──▶ map(RawMessage) ──▶ GraphFlowStageActor   │    │
  │   │                                              │              │    │
  │   └──────────────────────────────────────────────┼─────────────┘     │
  │                                                  │ StreamElementIn   │
  │                                                  ▼                   │
  │                                         ┌────────────────┐           │
  │                                         │  Adapter Actor │           │
  │                                         │                │           │
  │   TopicSupervisor ──RouterUpdated──────▶│  currentRouter │────────────▶ RouterActor
  │   TopicSupervisor ──RouterLost─────────▶│   (immutable)  │           │    (remote)
  │                                         │                │           │
  │                                         │  if None:      │           │
  │                                         │    drop + warn │           │
  │                                         └────────────────┘           │
  └──────────────────────────────────────────────────────────────────────┘

  Timeline:

  t=0   Router joins cluster  →  Supervisor sends RouterUpdated(ref)  →  Adapter stores ref
  t=1   Stream emits data     →  GraphStage sends to Adapter          →  Adapter forwards to Router
  t=2   Router crashes        →  Supervisor sends RouterLost          →  Adapter stores None
  t=3   Stream emits data     →  GraphStage sends to Adapter          →  Adapter drops (warns once)
  t=4   Router recovers       →  Supervisor sends RouterUpdated(ref)  →  Adapter resumes forwarding
```

The stream never pauses. The adapter absorbs all topology changes silently.

---

#### KillSwitches

**The problem**: Once `.run()` is called, the Alpakka stream is a self-sustaining materialized unit — it lives independently of the actor that started it. Simply stopping the `ConsumerActor` does **not** stop the stream. Without an explicit handle, the stream runs as an orphan: consuming from Kafka, holding consumer group leases, with no router to forward data to.

**The solution**: A `UniqueKillSwitch` is inserted into the stream graph at startup and the reference is stored inside the actor's closure. The actor's `PostStop` / `PreRestart` signal is the trigger.

```
  Startup — stream is materialized:

  ┌────────────────────────────────────────────────────────────────────────────┐
  │  ConsumerActor (actor thread)                                              │
  │                                                                            │
  │   val (killSwitch, streamDone) =                                           │
  │       KafkaSource                                                          │
  │         .viaMat(KillSwitches.single)(Keep.right)  ◀── inserted here        │
  │         .map(RawMessage)                                                   │
  │         .via(actorFlow)                                                    │
  │         .toMat(Sink.ignore)(Keep.both)                                     │
  │         .run()                          ◀── stream now lives independently │
  │                                                                            │
  │   killSwitch  ◀── stored in actor closure                                  │
  └────────────────────────────────────────────────────────────────────────────┘

  Shutdown — actor receives PostStop or PreRestart signal:

  ┌─────────────────┐     PostStop      ┌──────────────────────────────────────┐
  │  TopicSupervisor│ ───────────────▶  │  ConsumerActor                       │
  │  (or crash)     │                   │                                      │
  └─────────────────┘                   │  receiveSignal:                      │
                                        │    case PostStop  => killSwitch.shutdown()
                                        │    case PreRestart => killSwitch.shutdown()
                                        └───────────────┬──────────────────────┘
                                                        │
                                                        ▼
                                        ┌──────────────────────────────────────┐
                                        │  Stream Graph (reactive streams)      │
                                        │                                       │
                                        │  KillSwitch ──▶ signals completion   │
                                        │             ──▶ upstream drains       │
                                        │             ──▶ Kafka consumer closes │
                                        │             ──▶ group lease released  │
                                        └──────────────────────────────────────┘
```

### 4. Stream Bridge (`GraphFlowStageActor.scala`)
- **Responsibility**: A custom `GraphStage` that transforms backpressured stream elements into Actor messages.
- **Robustness**: Wraps cross-node communication in explicit `try-catch` blocks. Drops or logs dead-letters during network transients instead of failing the stream, ensuring the continuous pipeline never halts.

---

## Data Pipeline Flow

```
  ┌────────────────────────────────────────────────────────────────────────────────┐
  │                                                                                │
  │   [1] Kafka Broker                                                             │
  │       Topic: amr.001                                                           │
  │       Payload: opaque Array[Byte]                                              │
  │           │                                                                    │
  │           │  Alpakka committableSource                                         │
  │           ▼                                                                    │
  │   [2] map to RawMessage                                                        │
  │       RawMessage(topic = "amr.001", key = "k1", value = Array[Byte])           │
  │           │                                                                    │
  │           │  via actorFlow                                                     │
  │           ▼                                                                    │
  │   [3] GraphFlowStageActor                                                      │
  │       Converts backpressured stream element → StreamElementIn(RawMessage)      │
  │       Sends to Adapter Actor via classic ActorRef (fire-and-forget)            │
  │           │                                                                    │
  │           │  actor message                                                     │
  │           ▼                                                                    │
  │   [4] Adapter Actor                                                            │
  │       Holds current RouterActor ref                                            │
  │       Forwards StreamElementIn(RawMessage) to RouterActor over Pekko Cluster   │
  │           │                                                                    │
  │           │  CBOR-serialized Pekko message over TCP                            │
  │           ▼                                                                    │
  │   [5] RouterActor  (Remote Node)                                               │
  │       Receives RawMessage, routes to Grpc clients (microservices)              │
  │           │                                                                    │
  │           ▼                                                                    │
  │   [6] Sink.ignore                                                              │
  │       Kafka offsets committed — consumer advances regardless of downstream     │
  │                                                                                │
  └────────────────────────────────────────────────────────────────────────────────┘
```

---

## Operations & Extensibility

### Extending for New Protocols
If you wish to consume from RabbitMQ, MQTT, or Redis instead of Kafka in the future, you only need to implement a new `ConsumerActor` source. The rest of the cluster routing and the router schema remain unchanged because the handler treats all elements as opaque `Array[Byte]`.

### Starting the Node
```bash
sbt clean run
# Select com.example.KafkaConsumerNode
```

### Logging & Diagnostics
- `[TopicDiscovery] Found X/Y topics`: Active broker connection.
- `[ConsumerActor] Router discovery updated`: Cluster connectivity established.
- `[ConsumerActor/Adapter] Forwarding data packet`: The continuous pump is active.

> **Next Steps**: See the **Stream Router** documentation to understand how this continuous flow is exposed as an open/close "Tap" to the microservices.
