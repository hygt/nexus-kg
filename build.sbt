/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
  align.tokens = [
    { code = "=>", owner = "Case" }
    { code = "?", owner = "Case" }
    { code = "extends", owner = "Defn.(Class|Trait|Object)" }
    { code = "//", owner = ".*" }
    { code = "{", owner = "Template" }
    { code = "}", owner = "Template" }
    { code = ":=", owner = "Term.ApplyInfix" }
    { code = "++=", owner = "Term.ApplyInfix" }
    { code = "+=", owner = "Term.ApplyInfix" }
    { code = "%", owner = "Term.ApplyInfix" }
    { code = "%%", owner = "Term.ApplyInfix" }
    { code = "%%%", owner = "Term.ApplyInfix" }
    { code = "->", owner = "Term.ApplyInfix" }
    { code = "?", owner = "Term.ApplyInfix" }
    { code = "<-", owner = "Enumerator.Generator" }
    { code = "?", owner = "Enumerator.Generator" }
    { code = "=", owner = "(Enumerator.Val|Defn.(Va(l|r)|Def|Type))" }
  ]
}
 */

// Dependency versions
val adminVersion                = "225b1a92"
val commonsVersion              = "0.13.0"
val storageVersion              = "97544e65"
val sourcingVersion             = "0.16.3"
val akkaVersion                 = "2.5.23"
val akkaCorsVersion             = "0.4.1"
val akkaHttpVersion             = "10.1.8"
val akkaPersistenceInMemVersion = "2.5.1.1"
val akkaPersistenceCassVersion  = "0.98"
val alpakkaVersion              = "1.0.2"
val catsVersion                 = "1.6.1"
val catsEffectVersion           = "1.3.1"
val circeVersion                = "0.11.1"
val journalVersion              = "3.0.19"
val logbackVersion              = "1.2.3"
val mockitoVersion              = "1.5.11"
val monixVersion                = "3.0.0-RC2"
val pureconfigVersion           = "0.11.1"
val shapelessVersion            = "2.3.3"
val scalaTestVersion            = "3.0.8"
val kryoVersion                 = "0.5.2"
val s3mockVersion               = "0.2.5"

// Dependencies modules
lazy val adminClient          = "ch.epfl.bluebrain.nexus" %% "admin-client"               % adminVersion
lazy val elasticSearchClient  = "ch.epfl.bluebrain.nexus" %% "elasticsearch-client"       % commonsVersion
lazy val sparqlClient         = "ch.epfl.bluebrain.nexus" %% "sparql-client"              % commonsVersion
lazy val commonsCore          = "ch.epfl.bluebrain.nexus" %% "commons-core"               % commonsVersion
lazy val commonsTest          = "ch.epfl.bluebrain.nexus" %% "commons-test"               % commonsVersion
lazy val sourcingProjections  = "ch.epfl.bluebrain.nexus" %% "sourcing-projections"       % sourcingVersion
lazy val storageClient        = "ch.epfl.bluebrain.nexus" %% "storage-client"             % storageVersion
lazy val akkaCluster          = "com.typesafe.akka"       %% "akka-cluster"               % akkaVersion
lazy val akkaClusterSharding  = "com.typesafe.akka"       %% "akka-cluster-sharding"      % akkaVersion
lazy val akkaDistributedData  = "com.typesafe.akka"       %% "akka-distributed-data"      % akkaVersion
lazy val akkaHttp             = "com.typesafe.akka"       %% "akka-http"                  % akkaHttpVersion
lazy val akkaHttpCors         = "ch.megard"               %% "akka-http-cors"             % akkaCorsVersion
lazy val akkaHttpTestKit      = "com.typesafe.akka"       %% "akka-http-testkit"          % akkaHttpVersion
lazy val akkaPersistence      = "com.typesafe.akka"       %% "akka-persistence"           % akkaVersion
lazy val akkaPersistenceCass  = "com.typesafe.akka"       %% "akka-persistence-cassandra" % akkaPersistenceCassVersion
lazy val akkaPersistenceInMem = "com.github.dnvriend"     %% "akka-persistence-inmemory"  % akkaPersistenceInMemVersion
lazy val akkaSlf4j            = "com.typesafe.akka"       %% "akka-slf4j"                 % akkaVersion
lazy val akkaStream           = "com.typesafe.akka"       %% "akka-stream"                % akkaVersion
lazy val alpakkaS3            = "com.lightbend.akka"      %% "akka-stream-alpakka-s3"     % alpakkaVersion
lazy val catsCore             = "org.typelevel"           %% "cats-core"                  % catsVersion
lazy val catsEffect           = "org.typelevel"           %% "cats-effect"                % catsEffectVersion
lazy val circeCore            = "io.circe"                %% "circe-core"                 % circeVersion
lazy val journalCore          = "io.verizon.journal"      %% "core"                       % journalVersion
lazy val mockito              = "org.mockito"             %% "mockito-scala"              % mockitoVersion
lazy val logbackClassic       = "ch.qos.logback"          % "logback-classic"             % logbackVersion
lazy val monixEval            = "io.monix"                %% "monix-eval"                 % monixVersion
lazy val pureconfig           = "com.github.pureconfig"   %% "pureconfig"                 % pureconfigVersion
lazy val scalaTest            = "org.scalatest"           %% "scalatest"                  % scalaTestVersion
lazy val shapeless            = "com.chuusai"             %% "shapeless"                  % shapelessVersion
lazy val kryo                 = "com.github.romix.akka"   %% "akka-kryo-serialization"    % kryoVersion
lazy val s3mock               = "io.findify"              %% "s3mock"                     % s3mockVersion

lazy val kg = project
  .in(file("."))
  .settings(testSettings, buildInfoSettings)
  .enablePlugins(BuildInfoPlugin, ServicePackagingPlugin)
  .settings(
    name       := "kg",
    moduleName := "kg",
    // Remove the 76 minimum coverage once we reach back > 80% coverage
    coverageMinimum := 76,
    libraryDependencies ++= Seq(
      adminClient,
      akkaDistributedData,
      akkaHttp,
      akkaHttpCors,
      akkaPersistenceCass,
      akkaStream,
      akkaSlf4j,
      akkaCluster,
      alpakkaS3,
      catsCore,
      catsEffect,
      circeCore,
      elasticSearchClient,
      journalCore,
      kryo,
      logbackClassic,
      monixEval,
      pureconfig,
      sourcingProjections,
      sparqlClient,
      storageClient,
      akkaHttpTestKit      % Test,
      akkaPersistenceInMem % Test,
      commonsTest          % Test,
      mockito              % Test,
      s3mock               % Test,
      scalaTest            % Test
    ),
    cleanFiles ++= (baseDirectory.value * "ddata*").get
  )

lazy val testSettings = Seq(
  Test / testOptions       += Tests.Argument(TestFrameworks.ScalaTest, "-o", "-u", "target/test-reports"),
  Test / parallelExecution := false
)

lazy val buildInfoSettings = Seq(
  buildInfoKeys    := Seq[BuildInfoKey](version),
  buildInfoPackage := "ch.epfl.bluebrain.nexus.kg.config"
)

inThisBuild(
  List(
    homepage := Some(url("https://github.com/BlueBrain/nexus-kg")),
    licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
    scmInfo  := Some(ScmInfo(url("https://github.com/BlueBrain/nexus-kg"), "scm:git:git@github.com:BlueBrain/nexus-kg.git")),
    developers := List(
      Developer("bogdanromanx", "Bogdan Roman", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("hygt", "Henry Genet", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("umbreak", "Didac Montero Mendez", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/")),
      Developer("wwajerowicz", "Wojtek Wajerowicz", "noreply@epfl.ch", url("https://bluebrain.epfl.ch/"))
    ),
    // These are the sbt-release-early settings to configure
    releaseEarlyWith              := BintrayPublisher,
    releaseEarlyNoGpg             := true,
    releaseEarlyEnableSyncToMaven := false
  )
)

addCommandAlias("review", ";clean;scalafmtSbt;test:scalafmtCheck;scalafmtSbtCheck;coverage;scapegoat;test;coverageReport;coverageAggregate")
