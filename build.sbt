name := "cats-effect-tutorial"

version := "2.2.0"

scalaVersion := "2.12.12"

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.0.0-M4" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
