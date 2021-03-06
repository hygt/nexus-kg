package ch.epfl.bluebrain.nexus.kg.storage

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.NoSuchElementException

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3.S3Attributes
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, Materializer}
import cats.effect._
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.StorageConfig
import ch.epfl.bluebrain.nexus.kg.resources.ResId
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.storage.Storage._

import scala.concurrent.{ExecutionContext, Future}

object S3StorageOperations {

  final class Verify[F[_]](storage: S3Storage)(implicit F: Effect[F], as: ActorSystem, config: StorageConfig)
      extends VerifyStorage[F] {

    private implicit val mt: Materializer = ActorMaterializer()

    override def apply: F[Either[String, Unit]] = {
      val future = IO(
        S3.listBucket(storage.bucket, None)
          .withAttributes(S3Attributes.settings(storage.settings.toAlpakka(config.derivedKey)))
          .runWith(Sink.head))
      IO.fromFuture(future)
        .attempt
        .map {
          case Right(_)                        => Right(())
          case Left(_: NoSuchElementException) => Right(()) // bucket is empty, that is fine
          case Left(e)                         => Left(s"Error accessing S3 bucket '${storage.bucket}': ${e.getMessage}")
        }
        .to[F]
    }
  }

  final class Fetch[F[_]](storage: S3Storage)(implicit F: Effect[F], as: ActorSystem, config: StorageConfig)
      extends FetchFile[F, AkkaSource] {

    private implicit val mt: Materializer = ActorMaterializer()

    override def apply(fileMeta: FileAttributes): F[AkkaSource] = {
      val future = IO(
        S3.download(storage.bucket, URLDecoder.decode(fileMeta.path.toString, UTF_8.toString))
          .withAttributes(S3Attributes.settings(storage.settings.toAlpakka(config.derivedKey)))
          .runWith(Sink.head))
      IO.fromFuture(future)
        .flatMap {
          case Some((source, _)) => IO.pure(source: AkkaSource)
          case None              => IO.raiseError(KgError.RemoteFileNotFound(fileMeta.location))
        }
        .handleErrorWith {
          case e: KgError => IO.raiseError(e)
          case e: Throwable =>
            IO.raiseError(
              KgError.DownstreamServiceError(
                s"Error fetching S3 object with key '${fileMeta.path}' in bucket '${storage.bucket}': ${e.getMessage}"))
        }
        .to[F]
    }

  }

  final class Save[F[_]](storage: S3Storage)(implicit F: Effect[F], as: ActorSystem, config: StorageConfig)
      extends SaveFile[F, AkkaSource] {

    private implicit val ec: ExecutionContext = as.dispatcher
    private implicit val mt: Materializer     = ActorMaterializer()

    private val attributes = S3Attributes.settings(storage.settings.toAlpakka(config.derivedKey))

    override def apply(id: ResId, fileDesc: FileDescription, source: AkkaSource): F[FileAttributes] = {
      val key            = mangle(storage.ref, fileDesc.uuid, fileDesc.filename)
      val s3Sink         = S3.multipartUpload(storage.bucket, key).withAttributes(attributes)
      val metaDataSource = S3.getObjectMetadata(storage.bucket, key).withAttributes(attributes)

      val future = source
        .alsoToMat(digestSink(storage.algorithm))(Keep.right)
        .toMat(s3Sink) {
          case (digFuture, ioFuture) =>
            digFuture.zipWith(ioFuture) {
              case (dig, io) =>
                metaDataSource.runWith(Sink.head).flatMap {
                  case Some(meta) =>
                    val summary = StoredSummary(io.location, Uri.Path(key), meta.contentLength, dig)
                    Future.successful(fileDesc.process(summary))
                  case None =>
                    Future.failed(KgError.InternalError(
                      s"Empty content fetching metadata for uploaded file '${fileDesc.filename}' to location '${io.location}'"))
                }
              case _ =>
                Future.failed(KgError.InternalError(
                  s"I/O error uploading file with contentType '${fileDesc.mediaType}' and filename '${fileDesc.filename}'"))
            }
        }
        .run()
        .flatten

      IO.fromFuture(IO(future))
        .handleErrorWith {
          case e: KgError => IO.raiseError(e)
          case e: Throwable =>
            IO.raiseError(KgError.DownstreamServiceError(
              s"Error uploading S3 object with filename '${fileDesc.filename}' in bucket '${storage.bucket}': ${e.getMessage}"))
        }
        .to[F]
    }
  }

  final class Link[F[_]](storage: S3Storage)(implicit F: Effect[F], as: ActorSystem, config: StorageConfig)
      extends LinkFile[F] {

    private implicit val ec: ExecutionContext = as.dispatcher
    private implicit val mt: Materializer     = ActorMaterializer()

    override def apply(id: ResId, fileDesc: FileDescription, key: Uri.Path): F[FileAttributes] = {
      val location: Uri = s"${storage.settings.address}/${storage.bucket}/$key"
      val future =
        S3.download(storage.bucket, URLDecoder.decode(key.toString, UTF_8.toString))
          .withAttributes(S3Attributes.settings(storage.settings.toAlpakka(config.derivedKey)))
          .runWith(Sink.head)
          .flatMap {
            case Some((source, meta)) =>
              source.runWith(digestSink(storage.algorithm)).map { dig =>
                FileAttributes(fileDesc.uuid,
                               location,
                               key,
                               fileDesc.filename,
                               fileDesc.mediaType,
                               meta.contentLength,
                               dig)
              }
            case None => Future.failed(KgError.RemoteFileNotFound(location))
          }

      IO.fromFuture(IO(future))
        .handleErrorWith {
          case e: KgError => IO.raiseError(e)
          case e: Throwable =>
            IO.raiseError(
              KgError.DownstreamServiceError(
                s"Error fetching S3 object with key '$key' in bucket '${storage.bucket}': ${e.getMessage}"))
        }
        .to[F]
    }
  }
}
