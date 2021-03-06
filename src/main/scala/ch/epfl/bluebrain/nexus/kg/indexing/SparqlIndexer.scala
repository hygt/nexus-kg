package ch.epfl.bluebrain.nexus.kg.indexing

import java.util.Properties

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.rdf.syntax._
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.SparqlServerOrUnexpectedFailure
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlResults, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.indexing.View.SparqlView
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.sourcing.projections._
import kamon.Kamon
import monix.execution.atomic.AtomicLong

import scala.collection.JavaConverters._

private class SparqlIndexerMapping[F[_]](view: SparqlView, resources: Resources[F])(implicit F: Monad[F],
                                                                                    project: Project) {

  /**
    * When an event is received, the current state is obtained and a [[SparqlWriteQuery]] is built.
    *
    * @param event event to be mapped to a Sparql insert query
    */
  final def apply(event: Event): F[Option[Identified[ProjectRef, SparqlWriteQuery]]] =
    view.resourceTag
      .filter(_.trim.nonEmpty)
      .map(resources.fetch(event.id, _, true))
      .getOrElse(resources.fetch(event.id, true))
      .value
      .map {
        case Right(res) if validSchema(view, res) && validTypes(view, res) => Some(buildInsertOrDeleteQuery(res))
        case Right(res) if validSchema(view, res)                          => Some(buildDeleteQuery(res))
        case _                                                             => None
      }

  private def buildInsertOrDeleteQuery(res: ResourceV): Identified[ProjectRef, SparqlWriteQuery] =
    if (res.deprecated && !view.includeDeprecated) buildDeleteQuery(res)
    else buildInsertQuery(res)

  private def buildInsertQuery(res: ResourceV): Identified[ProjectRef, SparqlWriteQuery] =
    res.id -> SparqlWriteQuery.replace(toGraphUri(res.id), res.value.graph)

  private def buildDeleteQuery(res: ResourceV): Identified[ProjectRef, SparqlWriteQuery] =
    res.id -> SparqlWriteQuery.drop(toGraphUri(res.id))

  private def toGraphUri(id: ResId): Uri = (id.value + "graph").toAkkaUri
}

@SuppressWarnings(Array("MaxParameters"))
object SparqlIndexer {

  // $COVERAGE-OFF$
  /**
    * Starts the index process for an sparql client
    *
    * @param view          the view for which to start the index
    * @param resources     the resources operations
    * @param project       the project to which the resource belongs
    * @param restartOffset a flag to decide whether to restart from the beginning or to resume from the previous offset
    */
  final def start[F[_]: Timer](view: SparqlView, resources: Resources[F], project: Project, restartOffset: Boolean)(
      implicit as: ActorSystem,
      ul: UntypedHttpClient[F],
      P: Projections[F, Event],
      F: Effect[F],
      uclRs: HttpClient[F, SparqlResults],
      config: AppConfig): StreamSupervisor[F, ProjectionProgress] = {

    val sparqlErrorMonadError             = ch.epfl.bluebrain.nexus.kg.instances.sparqlErrorMonadError
    implicit val indexing: IndexingConfig = config.sparql.indexing

    val properties: Map[String, String] = {
      val props = new Properties()
      props.load(getClass.getResourceAsStream("/blazegraph/index.properties"))
      props.asScala.toMap
    }

    import as.dispatcher
    val client = BlazegraphClient[F](config.sparql.base, view.index, config.sparql.akkaCredentials)
    val mapper = new SparqlIndexerMapping(view, resources)(sparqlErrorMonadError, project)
    val init =
      for {
        _ <- client.createNamespace(properties)
        _ <- if (view.rev > 1) client.copy(namespace = view.copy(rev = view.rev - 1).index).deleteNamespace
        else F.pure(true)
      } yield ()

    val processedEventsGauge = Kamon
      .gauge("kg_indexer_gauge")
      .refine(
        "type"         -> "sparql",
        "project"      -> project.projectLabel.show,
        "organization" -> project.organizationLabel,
        "viewId"       -> view.id.show
      )
    val processedEventsCounter = Kamon
      .counter("kg_indexer_counter")
      .refine(
        "type"         -> "sparql",
        "project"      -> project.projectLabel.show,
        "organization" -> project.organizationLabel,
        "viewId"       -> view.id.show
      )
    val processedEventsCount = AtomicLong(0L)

    TagProjection.start(
      ProjectionConfig
        .builder[F]
        .name(s"sparql-indexer-${view.index}")
        .tag(s"project=${view.ref.id}")
        .plugin(config.persistence.queryJournalPlugin)
        .retry[SparqlServerOrUnexpectedFailure](indexing.retry.retryStrategy)(sparqlErrorMonadError)
        .batch(indexing.batch, indexing.batchTimeout)
        .restart(restartOffset)
        .init(init)
        .mapping(mapper.apply)
        .index(inserts => client.bulk(inserts.removeDupIds: _*))
        .mapInitialProgress { p =>
          processedEventsCount.set(p.processedCount)
          processedEventsGauge.set(p.processedCount)
          F.unit
        }
        .mapProgress { p =>
          val previousCount = processedEventsCount.get()
          processedEventsGauge.set(p.processedCount)
          processedEventsCounter.increment(p.processedCount - previousCount)
          processedEventsCount.set(p.processedCount)
          F.unit
        }
        .build)
  }

  /**
    * Starts the index process for an sparql client
    *
    * @param view          the view for which to start the index
    * @param resources     the resources operations
    * @param project       the project to which the resource belongs
    * @param restartOffset a flag to decide whether to restart from the beginning or to resume from the previous offset
    */
  final def delay[F[_]: Timer: Effect](
      view: SparqlView,
      resources: Resources[F],
      project: Project,
      restartOffset: Boolean
  )(implicit as: ActorSystem,
    ul: UntypedHttpClient[F],
    uclRs: HttpClient[F, SparqlResults],
    config: AppConfig,
    P: Projections[F, Event],
  ): F[StreamSupervisor[F, ProjectionProgress]] =
    Effect[F].delay(start(view, resources, project, restartOffset))

  // $COVERAGE-ON$
}
