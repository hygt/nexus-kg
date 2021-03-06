package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination}
import ch.epfl.bluebrain.nexus.commons.sparql.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.indexing.View.{ElasticSearchView, SparqlView}
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.Resources.generateId
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{FileDescription, LinkDescription}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.SearchParams
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.{Fetch, Link, Save}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import io.circe.Json

class Files[F[_]: Effect: Timer](repo: Repo[F])(implicit config: AppConfig) {

  /**
    * Creates a file resource.
    *
    * @param storage    the storage where the file is going to be saved
    * @param fileDesc   the file description metadata
    * @param source     the source of the file
    * @tparam In the storage input type
    * @return either a rejection or the new resource representation in the F context
    */
  def create[In](storage: Storage, fileDesc: FileDescription, source: In)(implicit subject: Subject,
                                                                          project: Project,
                                                                          saveStorage: Save[F, In]): RejOrResource[F] =
    create(Id(project.ref, generateId(project.base)), storage, fileDesc, source)

  /**
    * Creates a file resource.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param fileDesc the file description metadata
    * @param source   the source of the file
    * @tparam In the storage input type
    * @return either a rejection or the new resource representation in the F context
    */
  def create[In](id: ResId, storage: Storage, fileDesc: FileDescription, source: In)(
      implicit subject: Subject,
      project: Project,
      saveStorage: Save[F, In]): RejOrResource[F] =
    repo.createFile(id, OrganizationRef(project.organizationUuid), storage, fileDesc, source)

  /**
    * Replaces a file resource.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param rev      the last known revision of the resource
    * @param fileDesc the file description metadata
    * @param source   the source of the file
    * @tparam In the storage input type
    * @return either a rejection or the new resource representation in the F context
    */
  def update[In](id: ResId, storage: Storage, rev: Long, fileDesc: FileDescription, source: In)(
      implicit subject: Subject,
      saveStorage: Save[F, In]): RejOrResource[F] =
    repo.updateFile(id, storage, rev, fileDesc, source)

  /**
    * Creates a link to an existing file.
    *
    * @param storage    the storage where the file is going to be saved
    * @param source     the source representation in JSON-LD
    * @return either a rejection or the new resource representation in the F context
    */
  def createLink(storage: Storage,
                 source: Json)(implicit subject: Subject, project: Project, linkStorage: Link[F]): RejOrResource[F] =
    createLink(Id(project.ref, generateId(project.base)), storage, source)

  /**
    * Creates a link to an existing file.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param source   the source representation in JSON-LD
    * @return either a rejection or the new resource representation in the F context
    */
  def createLink(id: ResId, storage: Storage, source: Json)(implicit subject: Subject,
                                                            project: Project,
                                                            linkStorage: Link[F]): RejOrResource[F] = {
    EitherT.fromEither[F](LinkDescription(id, source)).flatMap { link =>
      val organizationRef = OrganizationRef(project.organizationUuid)
      repo.createLink(id, organizationRef, storage, FileDescription(link.filename, link.mediaType), link.path)
    }
  }

  /**
    * Updates a link to an existing file.
    *
    * @param id       the id of the resource
    * @param storage  the storage where the file is going to be saved
    * @param rev      the last known resource revision
    * @param source   the source representation in JSON-LD
    * @return either a rejection or the new resource representation in the F context
    */
  def updateLink(id: ResId, storage: Storage, rev: Long, source: Json)(implicit subject: Subject,
                                                                       linkStorage: Link[F]): RejOrResource[F] =
    EitherT.fromEither[F](LinkDescription(id, source)).flatMap { link =>
      repo.updateLink(id, storage, FileDescription(link.filename, link.mediaType), link.path, rev)
    }

  /**
    * Deprecates an existing file.
    *
    * @param id  the id of the file
    * @param rev the last known revision of the file
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def deprecate(id: ResId, rev: Long)(implicit subject: Subject): RejOrResource[F] =
    for {
      _          <- repo.get(id, rev, Some(fileRef)).toRight(NotFound(id.ref, Some(rev)))
      deprecated <- repo.deprecate(id, rev)
    } yield deprecated

  /**
    * Attempts to stream the file resource for the latest revision.
    *
    * @param id     the id of the resource
    * @return the optional streamed file in the F context
    */
  def fetch[Out](id: ResId)(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    fetch(repo.get(id, Some(fileRef)).toRight(notFound(id.ref)))

  /**
    * Attempts to stream the file resource with specific revision.
    *
    * @param id     the id of the resource
    * @param rev    the revision of the resource
    * @return the optional streamed file in the F context
    */
  def fetch[Out](id: ResId, rev: Long)(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    fetch(repo.get(id, rev, Some(fileRef)).toRight(notFound(id.ref, Some(rev))))

  /**
    * Attempts to stream the file resource with specific tag. The
    * tag is transformed into a revision value using the latest resource tag to revision mapping.
    *
    * @param id     the id of the resource
    * @param tag    the tag of the resource
    * @return the optional streamed file in the F context
    */
  def fetch[Out](id: ResId, tag: String)(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    fetch(repo.get(id, tag, Some(fileRef)).toRight(notFound(id.ref, tagOpt = Some(tag))))

  private def fetch[Out](rejOrResource: RejOrResource[F])(implicit fetchStorage: Fetch[F, Out]): RejOrFile[F, Out] =
    rejOrResource.subflatMap(resource => resource.file.toRight(NotFound(resource.id.ref))).flatMapF {
      case (storage, attr) => storage.fetch.apply(attr).map(out => Right((storage, attr, out)))
    }

  /**
    * Lists files on the given project
    *
    * @param view       optionally available default elasticSearch view
    * @param params     filter parameters of the resources
    * @param pagination pagination options
    * @return search results in the F context
    */
  def list(view: Option[ElasticSearchView], params: SearchParams, pagination: Pagination)(
      implicit tc: HttpClient[F, JsonResults],
      elasticSearch: ElasticSearchClient[F]): F[JsonResults] =
    listResources(view, params.copy(schema = Some(fileSchemaUri)), pagination)

  /**
    * Lists incoming resources for the provided 'file 'id''
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
    * Lists outgoing resources for the provided file ''id''
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

}

object Files {

  /**
    * @param config the implicitly available application configuration
    * @tparam F the monadic effect type
    * @return a new [[Files]] for the provided F type
    */
  final def apply[F[_]: Timer: Effect](implicit config: AppConfig, repo: Repo[F]): Files[F] =
    new Files[F](repo)
}
