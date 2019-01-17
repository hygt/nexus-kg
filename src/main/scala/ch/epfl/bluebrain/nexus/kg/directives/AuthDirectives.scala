package ch.epfl.bluebrain.nexus.kg.directives

import akka.http.javadsl.server.CustomRejection
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.FutureDirectives.onComplete
import akka.http.scaladsl.server.{AuthorizationFailedRejection, Directive0, Directive1}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.types.HttpRejection.UnauthorizedAccess
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.acls.AclsOps
import ch.epfl.bluebrain.nexus.kg.resources.Rejection
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.DownstreamServiceError
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Failure, Success, Try}

object AuthDirectives {

  /**
    * Extracts the credentials from the HTTP Authorization Header and builds the [[AuthToken]]
    */
  def token: Directive1[Option[AuthToken]] =
    extractCredentials.flatMap {
      case Some(OAuth2BearerToken(value)) => provide(Some(AuthToken(value)))
      case Some(_)                        => reject(AuthorizationFailedRejection)
      case _                              => provide(None)
    }

  /**
    * Checks if the current project has the provided permissions
    *
    * @param perms the permissions to check on the current project
    * @return pass if the ''perms'' is present on the current project, reject with [[AuthorizationFailedRejection]] otherwise
    */
  def hasPermission(perms: Set[Permission])(implicit
                                            acls: AccessControlLists,
                                            caller: Caller,
                                            project: Project): Directive0 =
    if (acls.exists(caller.identities, project.projectLabel, perms)) pass
    else reject(AuthorizationFailedRejection)

  /**
    * Retrieves the ACLs for all the identities in all the paths using the provided service account token.
    */
  def acls(implicit aclsOps: AclsOps, s: Scheduler): Directive1[AccessControlLists] =
    onComplete(aclsOps.fetch().runToFuture).flatMap {
      case Success(result)             => provide(result)
      case Failure(UnauthorizedAccess) => reject(AuthorizationFailedRejection)
      case Failure(err)                => reject(authorizationRejection(err))
    }

  /**
    * Authenticates the requested with the provided ''token'' and returns the ''caller''
    */
  def caller(implicit iamClient: IamClient[Task], token: Option[AuthToken], s: Scheduler): Directive1[Caller] =
    onComplete(iamClient.identities.runToFuture).flatMap {
      case Success(caller)             => provide(caller)
      case Failure(UnauthorizedAccess) => reject(AuthorizationFailedRejection)
      case Failure(err)                => reject(authorizationRejection(err))
    }

  /**
    * Signals that the authentication was rejected with an unexpected error.
    *
    * @param err the [[Rejection]]
    */
  final case class CustomAuthRejection(err: Rejection) extends CustomRejection

  private[directives] def authorizationRejection(err: Throwable) =
    CustomAuthRejection(
      DownstreamServiceError(
        Try(err.getMessage).filter(_ != null).getOrElse("error while authenticating on the downstream service")))
}
