package ch.epfl.bluebrain.nexus.kg.resolve

import cats.data.EitherT
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.ProjectNotFound._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.{IllegalContextValue, IncorrectId}
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.Resources.getOrAssignId
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.instances._
import ch.epfl.bluebrain.nexus.rdf.syntax._
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri, RootedGraph}
import io.circe.Json
import io.circe.syntax._

class Materializer[F[_]: Effect](resolution: ProjectResolution[F], projectCache: ProjectCache[F])(
    implicit config: AppConfig) {

  private val emptyJson = Json.obj()

  private def flattenCtx(rrefs: List[Ref], ccontextValue: Json)(
      implicit project: Project): EitherT[F, Rejection, Json] = {
    type JsonRefs = (Json, List[Ref])
    def inner(refs: List[Ref], contextValue: Json): EitherT[F, Rejection, JsonRefs] =
      (contextValue.asString, contextValue.asArray, contextValue.asObject) match {
        case (Some(str), _, _) =>
          val nextRef = Iri.absolute(str).toOption.map(Ref.apply)
          // format: off
          for {
            next  <- EitherT.fromOption[F](nextRef, IllegalContextValue(refs))
            _     <- if (refs.contains(next)) EitherT.leftT[F, Unit](IllegalContextValue(next :: refs)) else EitherT.rightT[F, Rejection](())
            res   <- resolveOrNotFound(next)
            value <- inner(next :: refs, res.value.contextValue)
          } yield value
        // format: on
        case (_, Some(arr), _) =>
          EitherT(arr.traverse(j => inner(refs, j).value).map {
            _.foldLeft[Either[Rejection, JsonRefs]](Right((Json.obj(), List.empty[Ref]))) {
              case (Right((accJ, accV)), Right((json, visited))) => Right((accJ deepMerge json, accV ++ visited))
              case (Left(rej), _)                                => Left(rej)
              case (_, Left(rej))                                => Left(rej)
            }
          })

        case (_, _, Some(_)) => EitherT.rightT[F, Rejection]((contextValue, refs))
        case (_, _, _)       => EitherT.leftT[F, JsonRefs](IllegalContextValue(refs): Rejection)
      }

    inner(rrefs, ccontextValue).map {
      case (`emptyJson`, _) =>
        Json.obj("@base" -> project.base.asString.asJson, "@vocab" -> project.vocab.asString.asJson) deepMerge resourceCtx.contextValue
      case (flattened, _) => flattened deepMerge resourceCtx.contextValue
    }
  }

  private def resolveOrNotFound(ref: Ref)(implicit project: Project): RejOrResource[F] =
    EitherT.fromOptionF(resolution(project.ref).resolve(ref), notFound(ref))

  /**
    * Resolves the context URIs using the [[ProjectResolution]] and once resolved, it replaces the URIs for the actual payload.
    *
    * @param source  the payload to resolve and flatten
    * @param id      the rootNode of the graph
    * @param project the project where the payload belongs to
    * @return Left(rejection) when failed and Right(value) when passed, wrapped in the effect type ''F''
    */
  def apply(source: Json, id: AbsoluteIri)(implicit project: Project): EitherT[F, Rejection, Value] =
    flattenCtx(List(id.ref), source.contextValue)
      .flatMap { ctx =>
        val resolved = source.replaceContext(Json.obj("@context" -> ctx))
        EitherT.fromEither[F](addIdOrReject(resolved, id)).flatMap(asGraphOrError(id, _, source, ctx))
      }

  /**
    * Resolves the context URIs using the [[ProjectResolution]] and once resolved, it replaces the URIs for the actual payload.
    *
    * @param source  the payload to resolve and flatten
    * @param project the project where the payload belongs to
    * @return Left(rejection) when failed and Right(value) when passed, wrapped in the effect type ''F''
    */
  def apply(source: Json)(implicit project: Project): EitherT[F, Rejection, (AbsoluteIri, Value)] =
    flattenCtx(Nil, source.contextValue)
      .flatMap { ctx =>
        val resolved = source.replaceContext(Json.obj("@context" -> ctx))
        val id       = getOrAssignId(resolved)
        EitherT.fromEither[F](addIdOrReject(resolved, id)).flatMap(asGraphOrError(id, _, source, ctx)).map(id -> _)
      }
  private def asGraphOrError(id: AbsoluteIri, resolved: Json, source: Json, ctx: Json): EitherT[F, Rejection, Value] =
    EitherT
      .fromEither[F](resolved.asGraph(id).map(Value(source, ctx, _)))
      .leftSemiflatMap(e => Rejection.fromMarshallingErr[F](id, e))

  private def addIdOrReject(json: Json, id: AbsoluteIri): Either[Rejection, Json] = {
    json.id match {
      case Some(`id`) => Right(json)
      case Some(_)    => Left(IncorrectId(id.ref))
      case None       => Right(json.id(id))
    }
  }

  /**
    * Attempts to find a resource with the provided ref using the [[ProjectResolution]]. Once found, it attempts to resolve the context URIs
    *
    * @param ref             the reference to a resource in the platform
    * @param resolver        the resolver used to perform the resolution
    * @param includeMetadata flag to decide whether or not the metadata should be included in the resuling graph
    * @return Left(rejection) when failed and Right(value) when passed, wrapped in the effect type ''F''
    */
  def apply(ref: Ref, resolver: Resolver, includeMetadata: Boolean): RejOrResourceV[F] =
    for {
      resource <- EitherT.fromOptionF(resolution(resolver).resolve(ref), notFound(ref))
      project  <- EitherT.fromOptionF(projectCache.get(resource.id.parent), projectNotFound(resource.id.parent))
      resolved <- if (includeMetadata) withMeta(resource)(project) else withoutMeta(resource)(project)
    } yield resolved

  def apply(ref: Ref)(implicit currentProject: Project): RejOrResourceV[F] =
    apply(ref, includeMetadata = false)

  /**
    * Attempts to find a resource with the provided ref using the [[ProjectResolution]]. Once found, it attempts to resolve the context URIs
    *
    * @param ref             the reference to a resource in the platform
    * @param includeMetadata flag to decide whether or not the metadata should be included in the resuling graph
    * @return Left(rejection) when failed and Right(value) when passed, wrapped in the effect type ''F''
    */
  def apply(ref: Ref, includeMetadata: Boolean)(implicit currentProject: Project): RejOrResourceV[F] =
    // format: off
    for {
      resource  <- EitherT.fromOptionF(resolution(currentProject.ref).resolve(ref), notFound(ref))
      project   <- if (resource.id.parent == currentProject.ref) EitherT.rightT[F, Rejection](currentProject)
                   else EitherT.fromOptionF(projectCache.get(resource.id.parent), projectNotFound(resource.id.parent))
      resolved  <- if (includeMetadata) withMeta(resource)(project) else withoutMeta(resource)(project)
    } yield resolved
  // format: on

  private def withoutMeta(resource: Resource)(implicit project: Project): RejOrResourceV[F] =
    apply(resource.value, resource.id.value)(project).map(value =>
      resource.map(_ => value.copy(graph = value.graph.removeMetadata)))

  /**
    * Materializes a resource flattening its context and producing a raw graph. While flattening the context references
    * are transitively resolved. If the provided context and resulting graph are empty, the parent project's base and
    * vocab settings are injected as the context in order to recompute the graph from the original JSON source.
    *
    * @param resource the resource to materialize
    */
  def apply(resource: Resource)(implicit project: Project): RejOrResourceV[F] =
    apply(resource.value, resource.id.value).map {
      case Value(json, flattened, graph) => resource.map(_ => Value(json, flattened, graph.removeMetadata))
    }

  /**
    * Materializes a resource flattening its context and producing a raw graph with the resource metadata. While flattening the context references
    * are transitively resolved. If the provided context and resulting graph are empty, the parent project's base and
    * vocab settings are injected as the context in order to recompute the graph from the original JSON source.
    *
    * @param resource the resource to materialize
    */
  def withMeta(resource: Resource, selfAsIri: Boolean = false)(implicit project: Project): RejOrResourceV[F] =
    apply(resource).map { resourceV =>
      val graph =
        RootedGraph(resourceV.value.graph.rootNode, resourceV.value.graph.triples ++ resourceV.metadata(selfAsIri))
      resourceV.map(_.copy(graph = graph))
    }

  /**
    * Transitively imports resources referenced by the primary node of the resource through ''owl:imports'' if the
    * resource has type ''owl:Ontology''.
    *
    * @param resId the resource id for which imports are looked up
    * @param graph the resource graph for which imports are looked up
    */
  def imports(resId: ResId, graph: Graph)(implicit project: Project): EitherT[F, Rejection, Set[ResourceV]] = {

    val currentRef = resId.value.ref
    def importsValues(id: AbsoluteIri, g: Graph): Set[Ref] =
      g.objects(IriNode(id), owl.imports).unorderedFoldMap {
        case IriNode(iri) => Set(iri.ref)
        case _            => Set.empty
      }

    def lookup(current: Map[Ref, ResourceV], remaining: Set[Ref]): EitherT[F, Rejection, Set[ResourceV]] = {
      def load(ref: Ref): EitherT[F, Rejection, (Ref, ResourceV)] =
        current
          .find(_._1 == ref)
          .map(tuple => EitherT.rightT[F, Rejection](tuple))
          .getOrElse(apply(ref).map(ref -> _))

      if (remaining.isEmpty) EitherT.rightT[F, Rejection](current.values.toSet)
      else {
        val batch: EitherT[F, Rejection, Set[(Ref, ResourceV)]] =
          remaining.toList.traverse(load).map(_.toSet)

        batch.flatMap { list =>
          val nextRemaining: Set[Ref] = list
            .flatMap {
              case (ref, res) => importsValues(ref.iri, res.value.graph).toList
            } - currentRef
          val nextCurrent: Map[Ref, ResourceV] = current ++ list.toMap
          lookup(nextCurrent, nextRemaining)
        }
      }
    }

    lookup(Map.empty, importsValues(resId.value, graph) - currentRef)
  }

}
