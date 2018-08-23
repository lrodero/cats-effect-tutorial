name := "cats-effect-tutorial"

version := "0.5"

scalaVersion := "2.12.2"

libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0-RC3" withSources() withJavadoc()

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ypartial-unification")


