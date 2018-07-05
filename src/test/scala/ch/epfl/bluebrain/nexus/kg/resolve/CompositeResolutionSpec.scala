package ch.epfl.bluebrain.nexus.kg.resolve

import java.time.Clock
import java.util.UUID
import java.util.regex.Pattern.quote

import cats.instances.try_._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.rdf.syntax.node.unsafe._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.resources.Ref
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import org.scalatest.{Matchers, OptionValues, TryValues, WordSpecLike}

import scala.util.Try

class CompositeResolutionSpec extends WordSpecLike with Resources with Matchers with TryValues with OptionValues {

  "CompositeResolution" should {

    implicit val clock = Clock.systemUTC

    val resource1Uri: AbsoluteIri = url"http://nexus.example.com/resources/static/${UUID.randomUUID().toString}"
    val resource2Uri: AbsoluteIri = url"http://nexus.example.com/resources/static/${UUID.randomUUID().toString}"
    val staticResolution1 = StaticResolution[Try](
      Map(
        resource1Uri -> jsonContentOf("/resolve/simple-resource.json",
                                      Map(quote("{id}")     -> resource1Uri.show,
                                          quote("{random}") -> UUID.randomUUID().toString))
      ))
    val staticResolution2 = StaticResolution[Try](
      Map(
        resource2Uri -> jsonContentOf("/resolve/simple-resource.json",
                                      Map(quote("{id}")     -> resource2Uri.show,
                                          quote("{random}") -> UUID.randomUUID().toString))
      ))
    val staticResolution3 = StaticResolution[Try](
      Map(
        resource1Uri -> jsonContentOf("/resolve/simple-resource.json",
                                      Map(quote("{id}")     -> resource1Uri.show,
                                          quote("{random}") -> UUID.randomUUID().toString))
      ))

    val compositeResolution = CompositeResolution[Try](List(staticResolution1, staticResolution2, staticResolution3))

    "return the resource from the first resolver which returns the resource" in {

      compositeResolution.resolve(Ref(resource1Uri)).success.value.value shouldEqual staticResolution1
        .resolve(Ref(resource1Uri))
        .success
        .value
        .value
      compositeResolution.resolve(Ref(resource2Uri)).success.value.value shouldEqual staticResolution2
        .resolve(Ref(resource2Uri))
        .success
        .value
        .value
    }

    "return all the resources from resolvers that return Some(res)" in {
      compositeResolution.resolveAll(Ref(resource1Uri)).success.value shouldEqual List(
        staticResolution1
          .resolve(Ref(resource1Uri))
          .success
          .value
          .value,
        staticResolution3
          .resolve(Ref(resource1Uri))
          .success
          .value
          .value
      )

    }
  }

}