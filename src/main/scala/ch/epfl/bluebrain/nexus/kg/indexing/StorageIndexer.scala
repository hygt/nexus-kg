package ch.epfl.bluebrain.nexus.kg.indexing

import akka.actor.ActorSystem
import cats.MonadError
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, StorageCache}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.Storages.TimedStorage
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressStorage.Volatile
import ch.epfl.bluebrain.nexus.sourcing.projections._
import ch.epfl.bluebrain.nexus.sourcing.retry.Retry
import journal.Logger

private class StorageIndexerMapping[F[_]: Timer](storages: Storages[F])(implicit projectCache: ProjectCache[F],
                                                                        F: MonadError[F, Throwable],
                                                                        indexing: IndexingConfig) {

  private implicit val retry: Retry[F, Throwable] = Retry[F, Throwable](indexing.retry.retryStrategy)
  private implicit val log                        = Logger[this.type]

  /**
    * Fetches the storage which corresponds to the argument event. If the resource is not found, or it's not
    * compatible to a storage the event is dropped silently.
    *
    * @param event event to be mapped to a storage
    */
  def apply(event: Event): F[Option[TimedStorage]] =
    fetchProject(event.id.parent).flatMap { implicit project =>
      storages.fetchStorage(event.id).value.map {
        case Left(err) =>
          log.error(s"Error on event '${event.id.show} (rev = ${event.rev})', cause: '${err.msg}'")
          None
        case Right(timedStorage) => Some(timedStorage)
      }
    }
}

object StorageIndexer {

  // $COVERAGE-OFF$
  /**
    * Starts the index process for storages across all projects in the system.
    *
    * @param storages     the storages operations
    * @param storageCache the distributed cache for storages
    */
  final def start[F[_]: Timer](storages: Storages[F], storageCache: StorageCache[F])(
      implicit
      projectCache: ProjectCache[F],
      as: ActorSystem,
      F: Effect[F],
      config: AppConfig): StreamSupervisor[F, ProjectionProgress] = {

    val kgErrorMonadError = ch.epfl.bluebrain.nexus.kg.instances.kgErrorMonadError

    implicit val indexing: IndexingConfig = config.keyValueStore.indexing

    val mapper = new StorageIndexerMapping[F](storages)
    TagProjection.start(
      ProjectionConfig
        .builder[F]
        .name("storage-indexer")
        .tag(s"type=${nxv.Storage.value.show}")
        .plugin(config.persistence.queryJournalPlugin)
        .retry[KgError](indexing.retry.retryStrategy)(kgErrorMonadError)
        .batch(indexing.batch, indexing.batchTimeout)
        .offset(Volatile)
        .mapping(mapper.apply)
        .index(_.traverse { case (storage, instant) => storageCache.put(storage)(instant) }(F) >> F.unit)
        .build)
  }

  /**
    * Starts the index process for storages across all projects in the system.
    *
    * @param storages     the storages operations
    * @param storageCache the distributed cache for storages
    */
  final def delay[F[_]: Timer: Effect](storages: Storages[F], storageCache: StorageCache[F])(
      implicit
      projectCache: ProjectCache[F],
      as: ActorSystem,
      config: AppConfig,
  ): F[StreamSupervisor[F, ProjectionProgress]] =
    Effect[F].delay(start(storages, storageCache))
  // $COVERAGE-ON$
}
