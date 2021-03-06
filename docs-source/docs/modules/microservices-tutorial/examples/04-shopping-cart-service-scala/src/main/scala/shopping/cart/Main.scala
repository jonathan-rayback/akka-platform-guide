package shopping.cart

import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement

// tag::ItemPopularityProjection[]
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry

// end::ItemPopularityProjection[]

object Main {

  def main(args: Array[String]): Unit = {
    ActorSystem[Nothing](Main(), "ShoppingCartService")
  }

  def apply(): Behavior[Nothing] = {
    Behaviors.setup[Nothing](context => new Main(context))
  }
}

class Main(context: ActorContext[Nothing])
    extends AbstractBehavior[Nothing](context) {
  val system = context.system

  AkkaManagement(system).start()
  ClusterBootstrap(system).start()

  ShoppingCart.init(system)

  // tag::ItemPopularityProjection[]
  val session = CassandraSessionRegistry(system).sessionFor(
    "akka.persistence.cassandra"
  ) // <1>
  // use same keyspace for the item_popularity table as the offset store
  val itemPopularityKeyspace =
    system.settings.config.getString(
      "akka.projection.cassandra.offset-store.keyspace")
  val itemPopularityRepository =
    new ItemPopularityRepositoryImpl(
      session,
      itemPopularityKeyspace)(
      system.executionContext
    ) // <2>

  ItemPopularityProjection.init(
    system,
    itemPopularityRepository
  ) // <3>
  // end::ItemPopularityProjection[]

  val grpcInterface =
    system.settings.config
      .getString("shopping-cart-service.grpc.interface")
  val grpcPort = system.settings.config
    .getInt("shopping-cart-service.grpc.port")
  ShoppingCartServer.start(
    grpcInterface,
    grpcPort,
    system,
    itemPopularityRepository)

  override def onMessage(msg: Nothing): Behavior[Nothing] =
    this
}
