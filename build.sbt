inThisBuild(List(
  organization := "com.dwolla",
  description := "Abstract CloudFormation custom resource Lambda that can be easily extended with custom functionality.",
  homepage := Some(url("https://github.com/Dwolla/scala-cloudformation-custom-resource")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  developers := List(
    Developer(
      "bpholt",
      "Brian Holt",
      "bholt+github@dwolla.com",
      url("https://dwolla.com")
    ),
  ),
  crossScalaVersions := Seq("2.13.5", "2.12.13"),
  scalaVersion := crossScalaVersions.value.head,
  startYear := Option(2016),
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),

  githubWorkflowJavaVersions := Seq("adopt@1.8", "adopt@1.11"),
  githubWorkflowTargetTags ++= Seq("v*"),
  githubWorkflowPublishTargetBranches :=
    Seq(RefPredicate.StartsWith(Ref.Tag("v"))),
  githubWorkflowPublish := Seq(
    WorkflowStep.Sbt(
      List("ci-release"),
      env = Map(
        "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
        "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
        "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
        "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
      )
    )
  ),
))

lazy val `scala-cloudformation-custom-resource` = (project in file("."))
  .settings(
    libraryDependencies ++= {
      val specs2Version = "4.7.0"
      val catsVersion = "2.5.0"
      val catsEffectVersion = "2.4.1"
      val circeVersion = "0.13.0"

      val amazonJavaSdks = List(    // exclude the SDKs we don't need, since they're pulled in transitively, to keep the size of the jar down
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-cloudformation"),
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-cognitoidentity"),
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-dynamodb"),
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-kinesis"),
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-kms"),
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-s3"),
        ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-sqs")
      )

      Seq(
        "org.typelevel" %% "cats-core" % catsVersion,
        "org.typelevel" %% "cats-effect" % catsEffectVersion,
        "com.amazonaws" % "aws-lambda-java-core" % "1.2.1",
        "com.amazonaws" % "aws-lambda-java-log4j2" % "1.0.0",
        "org.log4s" %% "log4s" % "1.8.2",
        "org.apache.logging.log4j" % "log4j-api" % "2.11.1",
        "org.apache.logging.log4j" % "log4j-core" % "2.11.1",
        "org.apache.logging.log4j" % "log4j-slf4j-impl" % "2.11.1",
        "org.apache.httpcomponents" % "httpclient" % "4.5.2",
        "com.amazonaws" % "aws-lambda-java-events" % "1.1.0" % Test excludeAll(amazonJavaSdks: _*),
        "org.specs2" %% "specs2-core" % specs2Version % Test,
        "org.specs2" %% "specs2-mock" % specs2Version % Test,
        "io.circe" %% "circe-literal" % circeVersion % Test,
        "org.typelevel" %% "cats-laws" % catsVersion % Test,
        "org.typelevel" %% "discipline-specs2" % "1.0.0" % Test,
        "org.specs2" %% "specs2-scalacheck" % specs2Version % Test,
      ) ++ Seq(
        "io.circe" %% "circe-core",
        "io.circe" %% "circe-generic",
        "io.circe" %% "circe-parser",
      ).map(_ % circeVersion)
    },
  )
