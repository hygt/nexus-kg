package ch.epfl.bluebrain.nexus.kg.resources

import java.time.{Clock, Instant, ZoneId}

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.Uri
import akka.stream.ActorMaterializer
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test
import ch.epfl.bluebrain.nexus.commons.test.io.{IOEitherValues, IOOptionValues}
import ch.epfl.bluebrain.nexus.commons.test.ActorSystemFixture
import ch.epfl.bluebrain.nexus.iam.client.types.Identity._
import ch.epfl.bluebrain.nexus.kg.KgError.RemoteFileNotFound
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.resources.file.File.{Digest, FileDescription, StoredSummary}
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.kg.storage.Storage.StorageOperations.{Fetch, Link, Save}
import ch.epfl.bluebrain.nexus.kg.storage.Storage.{DiskStorage, FetchFile, LinkFile, SaveFile}
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import io.circe.Json
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalactic.Equality
import org.scalatest._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//noinspection TypeAnnotation
class FilesSpec
    extends ActorSystemFixture("FilesSpec", true)
    with IOEitherValues
    with IOOptionValues
    with WordSpecLike
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Matchers
    with OptionValues
    with EitherValues
    with BeforeAndAfter
    with test.Resources
    with TestHelper
    with Inspectors {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(3 second, 15 milliseconds)

  private implicit val appConfig              = Settings(system).appConfig
  private implicit val clock: Clock           = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  private implicit val mat: ActorMaterializer = ActorMaterializer()
  private implicit val ctx: ContextShift[IO]  = IO.contextShift(ExecutionContext.global)
  private implicit val timer: Timer[IO]       = IO.timer(ExecutionContext.global)

  private implicit val repo                    = Repo[IO].ioValue
  private val saveFile: SaveFile[IO, String]   = mock[SaveFile[IO, String]]
  private val fetchFile: FetchFile[IO, String] = mock[FetchFile[IO, String]]
  private val linkFile: LinkFile[IO]           = mock[LinkFile[IO]]

  private val files: Files[IO] = Files[IO]

  before {
    Mockito.reset(saveFile)
    Mockito.reset(fetchFile)
    Mockito.reset(linkFile)
  }

  trait Base {
    implicit val subject: Subject = Anonymous
    val projectRef                = ProjectRef(genUUID)
    val base                      = Iri.absolute(s"http://example.com/base/").right.value
    val id                        = Iri.absolute(s"http://example.com/$genUUID").right.value
    val resId                     = Id(projectRef, id)
    val voc                       = Iri.absolute(s"http://example.com/voc/").right.value
    // format: off
    implicit val project = Project(resId.value, "proj", "org", None, base, voc, Map.empty, projectRef.id, genUUID, 1L, deprecated = false, Instant.EPOCH, subject.id, Instant.EPOCH, subject.id)
    // format: on

    val value      = Json.obj()
    val types      = Set[AbsoluteIri](nxv.File)
    val desc       = FileDescription("name", `text/plain(UTF-8)`)
    val source     = "some text"
    val location   = Uri("file:///tmp/other")
    val path       = Uri.Path("other")
    val attributes = desc.process(StoredSummary(location, path, 20L, Digest("MD5", "1234")))
    val storage    = DiskStorage.default(projectRef)
    val fileLink   = jsonContentOf("/resources/file-link.json")

    implicit val save: Save[IO, String] = (st: Storage) => if (st == storage) saveFile else throw new RuntimeException

    implicit val link: Link[IO] = (st: Storage) => if (st == storage) linkFile else throw new RuntimeException

    implicit val fetch: Fetch[IO, String] = (st: Storage) =>
      if (st == storage) fetchFile else throw new RuntimeException

    implicit val ignoreUuid: Equality[FileDescription] = (a: FileDescription, b: Any) =>
      b match {
        case FileDescription(_, filename, mediaType) => a.filename == filename && a.mediaType == mediaType
        case _                                       => false
    }
  }

  "A Files bundle" when {

    "performing create operations" should {

      "create a new File" in new Base {
        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        files.create(resId, storage, desc, source).value.accepted shouldEqual
          ResourceF.simpleF(resId, value, schema = fileRef, types = types).copy(file = Some(storage -> attributes))
      }

      "prevent creating a new File when save method fails" in new Base {
        saveFile(resId, desc, source) shouldReturn IO.raiseError(new RuntimeException("Error I/O"))
        whenReady(files.create(resId, storage, desc, source).value.unsafeToFuture().failed) {
          _ shouldBe a[RuntimeException]
        }
      }

    }

    "performing update operations" should {

      "update a file" in new Base {
        val updatedSource     = genString()
        val attributesUpdated = desc.process(StoredSummary(location, path, 100L, Digest("MD5", genString())))

        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        saveFile(resId, desc, updatedSource) shouldReturn IO.pure(attributesUpdated)

        files.create(resId, storage, desc, source).value.accepted shouldBe a[Resource]
        files.update(resId, storage, 1L, desc, updatedSource).value.accepted shouldEqual
          ResourceF
            .simpleF(resId, value, 2L, schema = fileRef, types = types)
            .copy(file = Some(storage -> attributesUpdated))
      }

      "prevent updating a file that does not exist" in new Base {
        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        files.update(resId, storage, 1L, desc, source).value.rejected[NotFound] shouldEqual NotFound(resId.ref)
      }
    }

    "performing linking operations" should {

      "create a new link" in new Base {
        linkFile(eqTo(resId), eqTo(desc), eqTo(path)) shouldReturn IO.pure(attributes)
        files.createLink(resId, storage, fileLink).value.accepted shouldEqual
          ResourceF.simpleF(resId, value, schema = fileRef, types = types).copy(file = Some(storage -> attributes))
      }

      "prevent creating a new link when save method fails" in new Base {
        linkFile(eqTo(resId), eqTo(desc), eqTo(path)) shouldReturn IO.raiseError(new RuntimeException("Error I/O"))
        whenReady(files.createLink(resId, storage, fileLink).value.unsafeToFuture().failed) {
          _ shouldBe a[RuntimeException]
        }
      }

      "update a link" in new Base {
        linkFile(eqTo(resId), eqTo(desc), eqTo(path)) shouldReturn IO.pure(attributes)
        val location2 = Uri("file:///tmp/other2")
        val path2     = Uri.Path("other2")
        val fileLink2 = fileLink.deepMerge(Json.obj("path" -> Json.fromString(path2.toString)))

        val attributesUpdated = desc.process(StoredSummary(location2, path2, 100L, Digest("MD5", genString())))
        linkFile(eqTo(resId), eqTo(desc), eqTo(path2)) shouldReturn IO.pure(attributesUpdated)

        files.createLink(resId, storage, fileLink).value.accepted shouldBe a[Resource]
        files.updateLink(resId, storage, 1L, fileLink2).value.accepted shouldEqual
          ResourceF
            .simpleF(resId, value, 2L, schema = fileRef, types = types)
            .copy(file = Some(storage -> attributesUpdated))
      }

      "prevent updating a link that does not exist" in new Base {
        linkFile(eqTo(resId), eqTo(desc), eqTo(path)) shouldReturn IO.raiseError(RemoteFileNotFound(location))
        files
          .updateLink(resId, storage, 1L, fileLink)
          .value
          .failed[RemoteFileNotFound] shouldEqual RemoteFileNotFound(location)
      }
    }

    "performing deprecate operations" should {

      "deprecate a file" in new Base {
        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        files.create(resId, storage, desc, source).value.accepted shouldBe a[Resource]
        files.deprecate(resId, 1L).value.accepted shouldEqual
          ResourceF
            .simpleF(resId, value, 2L, schema = fileRef, types = types, deprecated = true)
            .copy(file = Some(storage -> attributes))
      }

      "prevent deprecating a file already deprecated" in new Base {
        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        files.create(resId, storage, desc, source).value.accepted shouldBe a[Resource]
        files.deprecate(resId, 1L).value.accepted shouldBe a[Resource]
        files.deprecate(resId, 2L).value.rejected[ResourceIsDeprecated] shouldBe a[ResourceIsDeprecated]
      }
    }

    "performing read operations" should {

      "return a file" in new Base {
        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        fetchFile(attributes) shouldReturn IO.pure(source)
        files.create(resId, storage, desc, source).value.accepted shouldBe a[Resource]
        files.fetch(resId).value.accepted shouldEqual ((storage, attributes, source))
      }

      "return a specific revision of the file " in new Base {
        val updatedSource     = genString()
        val attributesUpdated = desc.process(StoredSummary(location, path, 100L, Digest("MD5", genString())))

        saveFile(resId, desc, source) shouldReturn IO.pure(attributes)
        fetchFile(attributes) shouldReturn IO.pure(source)
        saveFile(resId, desc, updatedSource) shouldReturn IO.pure(attributesUpdated)
        fetchFile(attributesUpdated) shouldReturn IO.pure(updatedSource)

        files.create(resId, storage, desc, source).value.accepted shouldBe a[Resource]
        files.update(resId, storage, 1L, desc, updatedSource).value.accepted shouldBe a[Resource]
        files.fetch(resId, 1L).value.accepted shouldEqual ((storage, attributes, source))
        files.fetch(resId, 2L).value.accepted shouldEqual ((storage, attributesUpdated, updatedSource))
      }

      "return NotFound when the provided file does not exists" in new Base {
        files.fetch(resId).value.rejected[NotFound] shouldEqual NotFound(resId.ref)
      }
    }
  }
}
