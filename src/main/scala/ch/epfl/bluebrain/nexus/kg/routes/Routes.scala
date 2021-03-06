package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.headers.{`WWW-Authenticate`, HttpChallenges, Location}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, PredefinedFromEntityUnmarshallers}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchFailure._
import ch.epfl.bluebrain.nexus.commons.http.directives.PrefixDirectives.uriPrefix
import ch.epfl.bluebrain.nexus.commons.http.{RdfMediaTypes, RejectionHandling}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.SparqlClientError
import ch.epfl.bluebrain.nexus.iam.client.IamClientError
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlLists, Caller}
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.KgError._
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinator
import ch.epfl.bluebrain.nexus.kg.cache.Caches._
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.tracing._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.AppInfoRoutes.HealthStatusGroup
import ch.epfl.bluebrain.nexus.kg.routes.HealthStatus._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.storage.client.StorageClientError
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.{cors, corsRejectionHandler}
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import io.circe.Json
import io.circe.parser.parse
import journal.Logger
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.util.control.NonFatal

object Routes {

  private[this] val logger = Logger[this.type]

  /**
    * @return an ExceptionHandler that ensures a descriptive message is returned to the caller
    */
  final val exceptionHandler: ExceptionHandler = {
    def completeGeneric(): Route =
      complete(InternalError("The system experienced an unexpected error, please try again later."): KgError)

    ExceptionHandler {
      case _: IamClientError.Unauthorized =>
        // suppress errors for authentication failures
        val status = KgError.kgErrorStatusFrom(AuthenticationFailed)
        val header = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        complete((status, List(header), AuthenticationFailed: KgError))
      case _: IamClientError.Forbidden =>
        // suppress errors for authorization failures
        complete(AuthorizationFailed: KgError)
      case err: NotFound =>
        // suppress errors for not found
        complete(err: KgError)
      case AuthenticationFailed =>
        // suppress errors for authentication failures
        val status = KgError.kgErrorStatusFrom(AuthenticationFailed)
        val header = `WWW-Authenticate`(HttpChallenges.oAuth2("*"))
        complete((status, List(header), AuthenticationFailed: KgError))
      case AuthorizationFailed =>
        // suppress errors for authorization failures
        complete(AuthorizationFailed: KgError)
      case err: UnacceptedResponseContentType =>
        // suppress errors for unaccepted response content type
        complete(err: KgError)
      case err: ProjectNotFound =>
        // suppress error
        complete(err: KgError)
      case err: OrganizationNotFound =>
        // suppress error
        complete(err: KgError)
      case err: ProjectIsDeprecated =>
        // suppress error
        complete(err: KgError)
      case err: RemoteFileNotFound =>
        // suppress error
        complete(err: KgError)
      case err: StorageClientError.InvalidPath =>
        // suppress error
        complete(StatusCodes.BadRequest -> (RemoteStorageError(err.reason): KgError))
      case err: StorageClientError.NotFound =>
        // suppress error
        complete(StatusCodes.NotFound -> (RemoteStorageError(err.reason): KgError))
      case err: StorageClientError =>
        // suppress error
        logger.error(s"Received unexpected response from remote storage: '${err.message}'")
        completeGeneric()
      case UnsupportedOperation =>
        // suppress error
        complete(UnsupportedOperation: KgError)
      case err: InvalidOutputFormat =>
        // suppress error
        complete(err: KgError)
      case ElasticSearchClientError(status, body) =>
        parse(body) match {
          case Right(json) => complete(status -> json)
          case Left(_)     => complete(status -> body)
        }
      case SparqlClientError(status, body) => complete(status -> body)
      case f: ElasticSearchFailure =>
        logger.error(s"Received unexpected response from ES: '${f.message}' with body: '${f.body}'")
        completeGeneric()
      case err: KgError =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
      case NonFatal(err) =>
        logger.error("Exception caught during routes processing", err)
        completeGeneric()
    }
  }

  /**
    * @return a complete RejectionHandler for all library and code rejections
    */
  final val rejectionHandler: RejectionHandler = {
    val custom = RejectionHandling.apply { r: Rejection =>
      logger.debug(s"Handling rejection '$r'")
      r
    }
    corsRejectionHandler withFallback custom withFallback RejectionHandling.notFound withFallback RejectionHandler.default
  }

  /**
    * Wraps the provided route with CORS, rejection and exception handling.
    *
    * @param route the route to wrap
    */
  final def wrap(route: Route)(implicit hc: HttpConfig): Route = {
    val corsSettings = CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
    cors(corsSettings) {
      handleExceptions(exceptionHandler) {
        handleRejections(rejectionHandler) {
          uriPrefix(hc.publicUri) {
            route
          }
        }
      }
    }
  }

  /**
    * Generates the routes for all the platform resources
    *
    * @param resources the resources operations
    */
  @SuppressWarnings(Array("MaxParameters"))
  def apply(resources: Resources[Task],
            resolvers: Resolvers[Task],
            views: Views[Task],
            storages: Storages[Task],
            schemas: Schemas[Task],
            files: Files[Task],
            tags: Tags[Task],
            coordinator: ProjectViewCoordinator[Task])(
      implicit system: ActorSystem,
      clients: Clients[Task],
      cache: Caches[Task],
      config: AppConfig,
  ): Route = {
    import clients._
    implicit val um: FromEntityUnmarshaller[String] =
      PredefinedFromEntityUnmarshallers.stringUnmarshaller
        .forContentTypes(RdfMediaTypes.`application/sparql-query`, MediaTypes.`text/plain`)

    implicit val projectCache: ProjectCache[Task] = cache.project
    implicit val viewCache: ViewCache[Task]       = cache.view

    val healthStatusGroup = HealthStatusGroup(
      new CassandraHealthStatus(),
      new ClusterHealthStatus(Cluster(system)),
      new IamHealthStatus(clients.iamClient),
      new AdminHealthStatus(clients.adminClient),
      new ElasticSearchHealthStatus(clients.elasticSearch),
      new SparqlHealthStatus(clients.sparql)
    )
    val appInfoRoutes = AppInfoRoutes(config.description, healthStatusGroup).routes

    def list(implicit acls: AccessControlLists, caller: Caller, project: Project): Route =
      (get & paginated & searchParams & pathEndOrSingleSlash & hasPermission(read) & extractUri) {
        (pagination, params, uri) =>
          trace("listResource") {
            implicit val u = uri
            val listed     = viewCache.getDefaultElasticSearch(project.ref).flatMap(resources.list(_, params, pagination))
            complete(listed.runWithStatus(OK))
          }
      }

    def projectEvents(implicit project: Project, acls: AccessControlLists, caller: Caller): Route =
      (pathPrefix("events") & get & pathEndOrSingleSlash) {
        trace("eventProjectResource") {
          new EventRoutes(acls, caller).routes(project)
        }
      }

    def createDefault(implicit acls: AccessControlLists, caller: Caller, project: Project): Route =
      (post & noParameter('rev.as[Long]) & projectNotDeprecated & pathEndOrSingleSlash & hasPermission(
        ResourceRoutes.write)) {
        entity(as[Json]) { source =>
          trace("createResource") {
            complete(resources.create(unconstrainedRef, source).value.runWithStatus(Created))
          }
        }
      }

    def routesSelector(segment: IdOrUnderscore)(implicit acls: AccessControlLists, caller: Caller, project: Project) =
      segment match {
        case Underscore                    => routeSelectorUndescore
        case SchemaId(`resolverSchemaUri`) => new ResolverRoutes(resolvers, tags).routes
        case SchemaId(`viewSchemaUri`)     => new ViewRoutes(views, tags, coordinator).routes
        case SchemaId(`shaclSchemaUri`)    => new SchemaRoutes(schemas, tags).routes
        case SchemaId(`fileSchemaUri`)     => new FileRoutes(files, resources, tags).routes
        case SchemaId(`storageSchemaUri`)  => new StorageRoutes(storages, tags).routes
        case SchemaId(schema)              => new ResourceRoutes(resources, tags, schema.ref).routes ~ list ~ createDefault
        case _                             => reject()
      }

    def routeSelectorUndescore(implicit acls: AccessControlLists, caller: Caller, project: Project) =
      pathPrefix(IdSegment) { id =>
        // format: off
        onSuccess(resources.fetch(Id(project.ref, id), selfAsIri = false).value.runToFuture) {
          case Right(resource) if resource.schema == resolverRef => new ResolverRoutes(resolvers, tags).routes(id)
          case Right(resource) if resource.schema == viewRef     => new ViewRoutes(views, tags, coordinator).routes(id)
          case Right(resource) if resource.schema == shaclRef    => new SchemaRoutes(schemas, tags).routes(id)
          case Right(resource) if resource.schema == fileRef     => new FileRoutes(files, resources, tags).routes(id)
          case Right(resource) if resource.schema == storageRef  => new StorageRoutes(storages, tags).routes(id)
          case Right(resource)                                   => new ResourceRoutes(resources, tags, resource.schema).routes(id) ~ list ~ createDefault
          case Left(_: Rejection.NotFound)                       => new ResourceRoutes(resources, tags, unconstrainedRef).routes(id) ~ list ~ createDefault
          case Left(err) => complete(err)
        }
        // format: on
      } ~ list ~ createDefault

    wrap(extractToken { implicit optToken =>
      extractCallerAcls.apply {
        implicit acls =>
          extractCaller.apply {
            implicit caller =>
              concat(
                (pathPrefix(config.http.prefix / "events") & pathEndOrSingleSlash) {
                  new GlobalEventRoutes(acls, caller).routes
                },
                (pathPrefix(config.http.prefix / "resources" / "events") & pathEndOrSingleSlash) {
                  new GlobalEventRoutes(acls, caller).routes
                },
                (pathPrefix(config.http.prefix / "resources" / Segment) & pathPrefix("events") & get & pathEndOrSingleSlash) {
                  label =>
                    org(label).apply { implicit organization =>
                      trace("eventOrganizationResource") {
                        new EventRoutes(acls, caller).routes(organization)
                      }
                    }
                },
                pathPrefix(config.http.prefix / Segment) { resourceSegment =>
                  project.apply { implicit project =>
                    resourceSegment match {
                      case "resources" =>
                        pathPrefix(IdSegmentOrUnderscore)(routesSelector) ~ list ~ createDefault ~ projectEvents
                      case segment => mapToSchema(segment).map(routesSelector).getOrElse(reject())
                    }
                  }
                }
              )
          }
      }
    } ~ appInfoRoutes)
  }

  private def mapToSchema(resourceSegment: String): Option[SchemaId] =
    resourceSegment match {
      case "views"     => Some(SchemaId(viewSchemaUri))
      case "resolvers" => Some(SchemaId(resolverSchemaUri))
      case "schemas"   => Some(SchemaId(shaclSchemaUri))
      case "storages"  => Some(SchemaId(storageSchemaUri))
      case "files"     => Some(SchemaId(fileSchemaUri))
      case _           => None

    }

}
