package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.{Effect, Timer}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.rdf.syntax._
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.shacl.ShaclEngine
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError.InternalError
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resolve.Materializer
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.RootedGraph
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.instances._
import ch.epfl.bluebrain.nexus.rdf.syntax._
import io.circe.Json
import org.apache.jena.rdf.model.Model

class Schemas[F[_]: Timer](repo: Repo[F])(implicit F: Effect[F], materializer: Materializer[F], config: AppConfig) {

  /**
    * Creates a new schema attempting to extract the id from the source. If a primary node of the resulting graph
    * is found:
    * <ul>
    *   <li>if it's an iri then its value will be used</li>
    *   <li>if it's a bnode a new iri will be generated using the base value</li>
    * </ul>
    *
    * @param source     the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(source: Json)(implicit subject: Subject, project: Project): RejOrResource[F] =
    materializer(source.addContext(shaclCtxUri)).flatMap {
      case (id, Value(_, _, graph)) => create(Id(project.ref, id), source.addContext(shaclCtxUri), graph.removeMetadata)
    }

  /**
    * Creates a new storage.
    *
    * @param id     the id of the storage
    * @param source the source representation in json-ld format
    * @return either a rejection or the newly created resource in the F context
    */
  def create(id: ResId, source: Json)(implicit subject: Subject, project: Project): RejOrResource[F] =
    materializer(source.addContext(shaclCtxUri), id.value).flatMap {
      case Value(_, _, graph) => create(id, source.addContext(shaclCtxUri), graph.removeMetadata)
    }

  /**
    * Updates an existing storage.
    *
    * @param id        the id of the resource
    * @param rev       the last known revision of the resource
    * @param source    the new source representation in json-ld format
    * @return either a rejection or the updated resource in the F context
    */
  def update(id: ResId, rev: Long, source: Json)(implicit subject: Subject, project: Project): RejOrResource[F] =
    for {
      _        <- repo.get(id, rev, Some(shaclRef)).toRight(NotFound(id.ref, Some(rev)))
      matValue <- materializer(source.addContext(shaclCtxUri), id.value)
      typedGraph = addSchemaType(id.value, matValue.graph.removeMetadata)
      types      = typedGraph.rootTypes.map(_.value)
      _       <- validateShacl(id, typedGraph)
      updated <- repo.update(id, rev, types, source.addContext(shaclCtxUri))
    } yield updated

  /**
    * Deprecates an existing storage.
    *
    * @param id  the id of the storage
    * @param rev the last known revision of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    repo.get(id, rev, Some(shaclRef)).toRight(NotFound(id.ref, Some(rev))).flatMap(_ => repo.deprecate(id, rev))

  /**
    * Fetches the latest revision of a storage.
    *
    * @param id the id of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, Some(shaclRef)).toRight(notFound(id.ref)).flatMap(materializer.withMeta(_))

  /**
    * Fetches the provided revision of a storage
    *
    * @param id  the id of the storage
    * @param rev the revision of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, rev: Long)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, rev, Some(shaclRef)).toRight(notFound(id.ref, Some(rev))).flatMap(materializer.withMeta(_))

  /**
    * Fetches the provided tag of a storage.
    *
    * @param id  the id of the storage
    * @param tag the tag of the storage
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String)(implicit project: Project): RejOrResourceV[F] =
    repo.get(id, tag, Some(shaclRef)).toRight(notFound(id.ref, tagOpt = Some(tag))).flatMap(materializer.withMeta(_))

  /**
    * Lists schemas on the given project
    *
    * @param view       optionally available default elasticSearch view
    * @param params     filter parameters of the resources
    * @param pagination pagination options
    * @return search results in the F context
    */
  def list(view: Option[ElasticSearchView], params: SearchParams, pagination: Pagination)(
      implicit tc: HttpClient[F, JsonResults],
      elasticSearch: ElasticSearchClient[F]): F[JsonResults] =
    listResources[F](view, params.copy(schema = Some(shaclSchemaUri)), pagination)

  /**
    * Lists incoming resources for the provided ''id''
    *
    * @param id         the resource id for which to retrieve the incoming links
    * @param view       optionally available default sparql view
    * @param pagination pagination options
    * @return search results in the F context
    */
  def listIncoming(id: AbsoluteIri, view: Option[SparqlView], pagination: FromPagination)(
      implicit sparql: BlazegraphClient[F]): F[LinkResults] =
    incoming(id, view, pagination)

  /**
    * Lists outgoing resources for the provided ''id''
    *
    * @param id                   the resource id for which to retrieve the outgoing links
    * @param view                 optionally available default sparql view
    * @param pagination           pagination options
    * @param includeExternalLinks flag to decide whether or not to include external links (not Nexus managed) in the query result
    * @return search results in the F context
    */
  def listOutgoing(id: AbsoluteIri,
                   view: Option[SparqlView],
                   pagination: FromPagination,
                   includeExternalLinks: Boolean)(implicit sparql: BlazegraphClient[F]): F[LinkResults] =
    outgoing(id, view, pagination, includeExternalLinks)

  private def create(id: ResId, source: Json, graph: RootedGraph)(implicit subject: Subject,
                                                                  project: Project): RejOrResource[F] = {
    val typedGraph = addSchemaType(id.value, graph)
    val types      = typedGraph.rootTypes.map(_.value)

    for {
      _        <- validateShacl(id, typedGraph)
      resource <- repo.create(id, OrganizationRef(project.organizationUuid), shaclRef, types, source)
    } yield resource
  }

  private def addSchemaType(id: AbsoluteIri, graph: RootedGraph): RootedGraph =
    RootedGraph(id, graph.triples + ((id.value, rdf.tpe, nxv.Schema): Triple))

  private def validateShacl(resId: ResId, data: RootedGraph)(implicit project: Project): EitherT[F, Rejection, Unit] =
    materializer.imports(resId, data).flatMap { resolved =>
      val resolvedSets = resolved.foldLeft(data.triples)(_ ++ _.value.graph.triples)
      val resolvedData = RootedGraph(data.rootNode, resolvedSets).as[Model]()
      ShaclEngine(resolvedData, reportDetails = true) match {
        case Some(r) if r.isValid() => EitherT.rightT[F, Rejection](())
        case Some(r)                => EitherT.leftT[F, Unit](InvalidResource(shaclRef, r))
        case _ =>
          EitherT(
            F.raiseError(InternalError(s"Unexpected error while attempting to validate schema '$shaclSchemaUri'")))
      }
    }
}

object Schemas {

  /**
    * @param config the implicitly available application configuration
    * @tparam F the monadic effect type
    * @return a new [[Schemas]] for the provided F type
    */
  final def apply[F[_]: Timer: Effect: Materializer](implicit config: AppConfig, repo: Repo[F]): Schemas[F] =
    new Schemas[F](repo)
}
