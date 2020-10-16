name := "cats-effect-tutorial"

version := "2.2.0"

scalaVersion := "2.12.12"

libraryDependencies += "org.typelevel" %% "cats-effect" % "2.2.0" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
