name := "cats-effect-tutorial"

version := "2.1.4"

scalaVersion := "2.12.8"

libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.4" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")
